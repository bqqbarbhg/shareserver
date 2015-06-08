package fi.aalto.legroup.shareserver;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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

        protected static void blockCopy(OutputStream out, InputStream in, long size) throws IOException {

            int bufferSize = 2048;
            byte[] buffer = new byte[bufferSize];

            long toRead = size;
            int numRead;
            do {
                int nextRead;
                if (toRead > (long)bufferSize)
                    nextRead = bufferSize;
                else
                    nextRead = (int)toRead;

                numRead = in.read(buffer, 0, nextRead);
                toRead -= numRead;

                out.write(buffer, 0, numRead);
            } while (toRead > (long)0);
        }

        public void handle(HttpExchange t) throws IOException {

            String uri = t.getRequestURI().toString();
            String method = t.getRequestMethod();

            if (uri.matches("[A-Za-z0-9/]*(\\.[A-Za-z0-9]+)?")) {

                if (method.equals("GET")) {

                    File file = new File(root + uri);

                    if (!file.exists()) {
                        String response = "{ \"error\": \"Path not found\", \"path\": \"" + uri + "\" }\n";
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
                        
                        long contentLength = file.length();

                        Headers headers = t.getResponseHeaders();
                        headers.set("Content-Type", "image/jpg");
                        t.sendResponseHeaders(200, contentLength);

                        FileInputStream in = new FileInputStream(file);
                        OutputStream out = t.getResponseBody();

                        blockCopy(out, in, contentLength);

                        out.close();
                        in.close();
                    }
                } else if (method.equals("PUT") || method.equals("POST")) {

                    boolean canOverwrite = method.equals("PUT");

                    int fileIndex = uri.lastIndexOf('/');
                    String pathname = uri.substring(0, fileIndex);
                    String filename = uri.substring(fileIndex);

                    File path = new File(root + pathname);

                    if (!path.exists()) {
                        String response = "{ \"error\": \"Path not found\", \"path\": \"" + pathname + "\" }\n";
                        Headers headers = t.getResponseHeaders();
                        headers.set("Content-Type", "application/json");
                        t.sendResponseHeaders(404, response.length());
                        OutputStream out = t.getResponseBody();
                        out.write(response.getBytes());
                        out.close();
                        return;
                    }

                    File file = new File(root + pathname + filename);

                    boolean isOverwrite = file.exists();
                    if (isOverwrite && !canOverwrite) {
                        String response = "{ \"error\": \"File already exits\", \"path\": \"" + uri + "\" }\n";
                        Headers headers = t.getResponseHeaders();
                        headers.set("Content-Type", "application/json");
                        t.sendResponseHeaders(409, response.length());
                        OutputStream out = t.getResponseBody();
                        out.write(response.getBytes());
                        out.close();
                        return;
                    }

                    int bufferSize = 2048;
                    byte[] buffer = new byte[bufferSize];

                    Headers requestHeaders = t.getRequestHeaders();
                    long contentLength = Long.parseLong(requestHeaders.getFirst("Content-Length"), 10);
                    InputStream in = t.getRequestBody();
                    FileOutputStream out = new FileOutputStream(file);

                    blockCopy(out, in, contentLength);

                    out.close();
                    in.close();

                    String response = "{ \"overwrite\": \"" + (isOverwrite ? "true" : "false") + "\", \"path\": \"" + uri + "\" }\n";
                    Headers headers = t.getResponseHeaders();
                    headers.set("Content-Type", "application/json");
                    int status = isOverwrite ? 200 : 201;
                    t.sendResponseHeaders(status, response.length());
                    OutputStream rout = t.getResponseBody();
                    rout.write(response.getBytes());
                    rout.close();
                }

            } else {
                String response = "{ \"error\": \"Invalid url\", \"url\": \"" + uri + "\" }\n";
                Headers headers = t.getResponseHeaders();
                headers.set("Content-Type", "application/json");
                t.sendResponseHeaders(400, response.length());
                OutputStream out = t.getResponseBody();
                out.write(response.getBytes());
                out.close();
            }
        }
    }
}
