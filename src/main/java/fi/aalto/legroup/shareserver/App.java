package fi.aalto.legroup.shareserver;

import java.io.IOException;
import java.io.OutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.InetSocketAddress;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.Headers;

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

        private String root = new String("D:/shareserver");

        public void handle(HttpExchange t) throws IOException {

            String uri = t.getRequestURI().toString();
            String method = t.getRequestMethod();

            if (uri.matches("[A-Za-z0-9/]*(\\.[A-Za-z0-9]+)?")) {

                if (method.equals("GET")) {

                    File file = new File(root + uri);

                    if (!file.exists()) {
                        String response = "{ \"error\": \"Path not found\" }\n";
                        Headers headers = t.getResponseHeaders();
                        headers.set("Content-Type", "application/json");
                        t.sendResponseHeaders(404, response.length());
                        OutputStream out = t.getResponseBody();
                        out.write(response.getBytes());
                        out.close();
                        return;
                    }

                    if (file.isDirectory()) {

                        File[] files = file.listFiles();

                        String response = "{ \"directory\": [";

                        for (File child : files) {

                            response += "{ \"name\": \"" + child.getName() + "\", ";
                            response += "\"modified\": \"" + child.lastModified() + "\" },\n";
                        }
                        response = response.substring(0, response.length() - 2);
                        response += "] }\n";

                        Headers headers = t.getResponseHeaders();
                        headers.set("Content-Type", "application/json");
                        t.sendResponseHeaders(200, response.length());
                        OutputStream out = t.getResponseBody();
                        out.write(response.getBytes());
                        out.close();
                    } else {

                        Headers headers = t.getResponseHeaders();
                        headers.set("Content-Type", "image/jpg");
                        t.sendResponseHeaders(200, file.length());

                        FileInputStream in = new FileInputStream(file);

                        byte[] buffer = new byte[2048];

                        OutputStream out = t.getResponseBody();
                        int numRead;
                        do {
                            numRead = in.read(buffer);
                            out.write(buffer, 0, numRead);
                        } while (numRead > 0);

                        out.close();
                    }
                }

            } else {
                String response = "{ \"error\": \"Invalid url\", \"url\": \"" + uri + "\" }\n";
                Headers headers = t.getResponseHeaders();
                headers.set("Content-Type", "application/json");
                t.sendResponseHeaders(404, response.length());
                OutputStream out = t.getResponseBody();
                out.write(response.getBytes());
                out.close();
            }
        }
    }
}
