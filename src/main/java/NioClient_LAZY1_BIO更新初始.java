import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class NioClient_LAZY1_BIO更新初始 {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 8000;
    private static final int BUFFER_SIZE = 1024;

    public static void main(String[] args) {
        try {
            // 创建选择器
            Selector selector = Selector.open();

            // 创建SocketChannel并注册到选择器
            SocketChannel socketChannel = SocketChannel.open();
            socketChannel.configureBlocking(false);
            socketChannel.connect(new InetSocketAddress(SERVER_HOST, SERVER_PORT));
            socketChannel.register(selector, SelectionKey.OP_CONNECT);

            // 处理事件循环
            while (true) {
                selector.select();
                Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();

                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();

                    if (key.isConnectable()) {
                        handleConnectEvent(key);
                    } else if (key.isReadable()) {
                        handleReadEvent(key);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void handleConnectEvent(SelectionKey key) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();

        if (socketChannel.isConnectionPending()) {
            socketChannel.finishConnect();
        }

        socketChannel.configureBlocking(false);
        socketChannel.register(key.selector(), SelectionKey.OP_READ);

        // 发送数据到服务器
        String message = "Hello, Server!";
        ByteBuffer buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
        socketChannel.write(buffer);
    }

    private static void handleReadEvent(SelectionKey key) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        int bytesRead = socketChannel.read(buffer);

        if (bytesRead > 0) {
            buffer.flip();
            byte[] data = new byte[bytesRead];
            buffer.get(data);
            String receivedMessage = new String(data, StandardCharsets.UTF_8);
            System.out.println("Received message from server: " + receivedMessage);
        } else if (bytesRead == -1) {
            // 连接关闭
            socketChannel.close();
        }
    }
}