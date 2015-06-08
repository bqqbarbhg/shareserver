package fi.aalto.legroup.shareserver;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Hello world!
 */
public class App 
{
    public static void main(String[] args) throws IOException
    {
        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
        server.createContext("/", new RootHandler());
        server.setExecutor(null);
        server.start();

    }

    static class RootHandler implements HttpHandler {

        public void handle(HttpExchange t) throws IOException {
            String response = t.getRequestMethod() + " " + t.getRequestURI().toString() + "\n";
            t.sendResponseHeaders(200, response.length());
            OutputStream out = t.getResponseBody();
            out.write(response.getBytes());
            out.close();
        }
    }
}
