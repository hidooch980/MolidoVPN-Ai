// =========================================================================
// Molido VPN • Cloudflare Worker Serverless VLESS Proxy (گزینه ب)
// 100% Free • Unlimited Bandwidth • Zero VPS Required
// Deploy to Cloudflare Workers (Dashboard -> Workers & Pages -> Create Worker)
// =========================================================================

// شناسه امنیتی اختصاصی شما (UUID) - می‌توانید با شناسه دلخواه خود جایگزین کنید
const USER_ID = 'd342d11e-d424-4583-b36e-524ab1f0afa4';

// آدرس سایت پوششی (Clean SNI / Proxy IP)
const PROXY_IP = ''; // در صورت تمایل می‌توانید IP تمیز وارد کنید

export default {
  async fetch(request, env, ctx) {
    try {
      const upgradeHeader = request.headers.get('Upgrade');
      if (!upgradeHeader || upgradeHeader !== 'websocket') {
        const url = new URL(request.url);
        switch (url.pathname) {
          case '/sub':
            return new Response(generateSubConfig(request.headers.get('Host')), {
              headers: { 'Content-Type': 'text/plain; charset=utf-8' }
            });
          case '/health':
            return new Response(JSON.stringify({ status: 'healthy', engine: 'Molido-Serverless-Edge' }), {
              headers: { 'Content-Type': 'application/json' }
            });
          default:
            // نمایش یک صفحه وب استاندارد جهت مبهم‌سازی در برابر فیلترینگ
            return new Response('<!DOCTYPE html><html><head><title>Welcome</title></head><body><h1>Server is operational.</h1></body></html>', {
              headers: { 'Content-Type': 'text/html' }
            });
        }
      } else {
        return await vlessOverWSHandler(request);
      }
    } catch (err) {
      return new Response(err.toString(), { status: 500 });
    }
  }
};

async function vlessOverWSHandler(request) {
  const webSocketPair = new WebSocketPair();
  const [client, server] = Object.values(webSocketPair);
  server.accept();

  let remoteSocketWrapper = { value: null };
  let isDns = false;

  const readableWebSocketStream = makeReadableWebSocketStream(server);

  readableWebSocketStream.pipeTo(new WritableStream({
    async write(chunk, controller) {
      if (isDns) return;

      if (remoteSocketWrapper.value) {
        const writer = remoteSocketWrapper.value.writable.getWriter();
        await writer.write(chunk);
        writer.releaseLock();
        return;
      }

      // اعتبارسنجی پروتکل VLESS
      const { hasError, message, addressType, portRemote, addressRemote, rawDataIndex, vlessVersion, isUDP } = processVlessHeader(chunk, USER_ID);
      
      if (hasError) {
        controller.error(message);
        return;
      }

      // پاسخ تایید اتصال VLESS
      const responseHeader = new Uint8Array([vlessVersion[0], 0]);
      const rawClientData = chunk.slice(rawDataIndex);

      // ایجاد سوکت اتصال به مقصد نهایی
      handleTCPOutBound(remoteSocketWrapper, addressType, addressRemote, portRemote, rawClientData, server, responseHeader);
    },
    close() {},
    abort(reason) {}
  })).catch((err) => {});

  return new Response(null, {
    status: 101,
    webSocket: client
  });
}

function processVlessHeader(vlessBuffer, userID) {
  if (vlessBuffer.byteLength < 24) return { hasError: true, message: 'Invalid data' };
  
  const version = new Uint8Array(vlessBuffer.slice(0, 1));
  const sliceUserUUID = new Uint8Array(vlessBuffer.slice(1, 17));
  const userUUID = stringifyUUID(sliceUserUUID);

  if (userUUID !== userID) return { hasError: true, message: 'Unauthorized user' };

  const optLength = new Uint8Array(vlessBuffer.slice(17, 18))[0];
  const command = new Uint8Array(vlessBuffer.slice(18 + optLength, 18 + optLength + 1))[0];
  const isUDP = command === 2;

  const portIndex = 18 + optLength + 1;
  const portRemote = (new DataView(vlessBuffer.slice(portIndex, portIndex + 2))).getUint16(0);

  let addressIndex = portIndex + 2;
  const addressType = (new Uint8Array(vlessBuffer.slice(addressIndex, addressIndex + 1)))[0];

  let addressLength = 0;
  let addressValueIndex = addressIndex + 1;
  let addressRemote = '';

  switch (addressType) {
    case 1: // IPv4
      addressLength = 4;
      addressRemote = new Uint8Array(vlessBuffer.slice(addressValueIndex, addressValueIndex + addressLength)).join('.');
      break;
    case 2: // Domain
      addressLength = (new Uint8Array(vlessBuffer.slice(addressValueIndex, addressValueIndex + 1)))[0];
      addressValueIndex += 1;
      addressRemote = new TextDecoder().decode(vlessBuffer.slice(addressValueIndex, addressValueIndex + addressLength));
      break;
    case 3: // IPv6
      addressLength = 16;
      const dataView = new DataView(vlessBuffer.slice(addressValueIndex, addressValueIndex + addressLength));
      const ipv6 = [];
      for (let i = 0; i < 8; i++) ipv6.push(dataView.getUint16(i * 2).toString(16));
      addressRemote = ipv6.join(':');
      break;
    default:
      return { hasError: true, message: `Unsupported addressType: ${addressType}` };
  }

  return {
    hasError: false,
    addressType,
    addressRemote,
    portRemote,
    rawDataIndex: addressValueIndex + addressLength,
    vlessVersion: version,
    isUDP
  };
}

async function handleTCPOutBound(remoteSocket, addressType, addressRemote, portRemote, rawClientData, webSocket, responseHeader) {
  try {
    const tcpSocket = connect({
      hostname: addressRemote,
      port: portRemote
    });
    remoteSocket.value = tcpSocket;
    const writer = tcpSocket.writable.getWriter();
    await writer.write(rawClientData);
    writer.releaseLock();

    tcpSocket.readable.pipeTo(new WritableStream({
      async write(chunk) {
        if (responseHeader) {
          webSocket.send(await new Blob([responseHeader, chunk]).arrayBuffer());
          responseHeader = null;
        } else {
          webSocket.send(chunk);
        }
      }
    })).catch(() => {});
  } catch (e) {}
}

function makeReadableWebSocketStream(webSocket) {
  return new ReadableStream({
    start(controller) {
      webSocket.addEventListener('message', (event) => {
        controller.enqueue(event.data);
      });
      webSocket.addEventListener('close', () => controller.close());
      webSocket.addEventListener('error', (err) => controller.error(err));
    }
  });
}

function stringifyUUID(bytes) {
  const hex = [];
  for (let i = 0; i < bytes.length; i++) {
    hex.push((bytes[i] < 16 ? '0' : '') + bytes[i].toString(16));
  }
  return [
    hex.slice(0, 4).join(''),
    hex.slice(4, 6).join(''),
    hex.slice(6, 8).join(''),
    hex.slice(8, 10).join(''),
    hex.slice(10, 16).join('')
  ].join('-');
}

function generateSubConfig(host) {
  return `vless://${USER_ID}@${host}:443?encryption=none&security=tls&sni=${host}&type=ws&host=${host}&path=%2F#Molido-Cloudflare-Edge`;
}
