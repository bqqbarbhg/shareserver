package fi.aalto.legroup.shareserver;

import java.lang.StringBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetSocketAddress;

import java.nio.file.Files;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.Headers;

import com.google.common.io.BaseEncoding;

/**
 * Lightweight file server: GET, PUT, POST, DELETE on files, GET to list directories, url = path.
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

            // This should fit in the L1 cache.
            int bufferSize = 16*1024;
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

        protected static void respondJson(HttpExchange t, int code, Object... params) throws IOException {

            StringBuilder builder = new StringBuilder();
            builder.append('{');

            if (params.length % 2 != 0) {
                throw new IllegalArgumentException("Expected even number of extra arguments (key/value pairs)");
            }

            for (int i = 0; i < params.length; i += 2) {

                Object keyObject = params[i];
                Object valueObject = params[i + 1];

                if (!(keyObject instanceof String)) {
                    throw new IllegalArgumentException("Expected String key for argument position " + i);
                }

                builder.append('"');
                builder.append((String) keyObject);
                builder.append("\": ");

                if (valueObject instanceof String) {
                    // Does not escape currently.
                    builder.append('"');
                    builder.append((String) valueObject);
                    builder.append('"');
                } else if (valueObject instanceof Integer) {
                    builder.append(valueObject.toString());
                } else if (valueObject instanceof Boolean) {
                    builder.append(valueObject.toString());
                } else {
                    throw new IllegalArgumentException("Expected JSON value " + valueObject.toString() + " at argument position " + i);
                }

                if (i + 2 < params.length) {
                    builder.append(',');
                }
            }

            builder.append('}');
            builder.append('\n');

            String response = builder.toString();

            Headers headers = t.getResponseHeaders();
            headers.set("Content-Type", "application/json");
            t.sendResponseHeaders(code, response.length());
            OutputStream out = t.getResponseBody();
            out.write(response.getBytes());
            out.close();
        }

        public void handle(HttpExchange t) throws IOException {

            String uri = t.getRequestURI().toString();
            String method = t.getRequestMethod();

            String auth = t.getRequestHeaders().getFirst("Authorization");
            if (auth == null) {
                respondJson(t, 401,
                    "error", "No authorization found");
                return;
            }

            String[] authParts = auth.split(" ", 2);
            if (authParts.length < 2) {
                respondJson(t, 401,
                    "error", "Invalid authorization header");
                return;
            }

            if (!authParts[0].equals("Basic")) {
                respondJson(t, 401,
                    "error", "Invalid authorization method",
                    "method", authParts[0]);
                return;
            }

            String expected = BaseEncoding.base64().encode("user:pass".getBytes());

            if (!authParts[1].equals(expected)) {
                respondJson(t, 403,
                    "error", "Forbidden");
                return;
            }

            if (uri.matches("[A-Za-z0-9/]*(\\.[A-Za-z0-9]+)?")) {

                if (method.equals("GET")) {

                    File file = new File(root + uri);

                    if (!file.exists()) {
                        respondJson(t, 404,
                            "error", "File does not exist",
                            "path", uri);
                        return;
                    }

                    String tag = Long.toString(file.lastModified(), 36);

                    Headers requestHeaders = t.getRequestHeaders();
                    String matchTag = requestHeaders.getFirst("If-Match");

                    if (matchTag != null && !matchTag.equals(tag)) {

                        respondJson(t, 412,
                            "error", "Entity tag differs from expected",
                            "expected", tag,
                            "path", uri);
                        return;
                    }

                    if (file.isDirectory()) {

                        File[] files = file.listFiles();

                        String response = "";
                        response += "{ \"name\": \"" + file.getName() + "\", ";
                        response += "\"directory\": true, ";
                        response += "\"etag\": \"" + Long.toString(file.lastModified(), 36) + "\", ";
                        response += "\"children\": [\n ";

                        for (File child : files) {

                            String mimeType = Files.probeContentType(child.toPath());
                            response += "{ \"name\": \"" + child.getName() + "\", ";
                            response += "\"directory\": " + child.isDirectory() + ", ";
                            response += "\"etag\": \"" + Long.toString(child.lastModified(), 36) + "\", ";
                            if (mimeType != null) {
                                response += "\"mime\": \"" + mimeType + "\", ";
                            } else {
                                response += "\"mime\": null, ";
                            }
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

                        String mimeType = Files.probeContentType(file.toPath());

                        Headers headers = t.getResponseHeaders();
                        if (mimeType != null) {
                            headers.set("Content-Type", mimeType);
                        }
                        headers.set("ETag", tag);
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
                        respondJson(t, 404,
                            "error", "Path not found",
                            "path", path);
                        return;
                    }

                    File file = new File(root + pathname + filename);

                    boolean isOverwrite = file.exists();
                    if (isOverwrite && !canOverwrite) {
                        respondJson(t, 409,
                            "error", "File already exists",
                            "path", uri);
                        return;
                    }

                    String oldTag = Long.toString(file.lastModified(), 36);

                    Headers requestHeaders = t.getRequestHeaders();
                    String matchTag = requestHeaders.getFirst("If-Match");

                    if (matchTag != null && !matchTag.equals(oldTag)) {

                        respondJson(t, 412,
                            "error", "Entity tag differs from expected",
                            "expected", oldTag,
                            "path", uri);
                        return;
                    }

                    long contentLength = Long.parseLong(requestHeaders.getFirst("Content-Length"));
                    InputStream in = t.getRequestBody();
                    FileOutputStream out = new FileOutputStream(file);

                    blockCopy(out, in, contentLength);

                    out.close();
                    in.close();

                    String newTag = Long.toString(file.lastModified(), 36);

                    Headers headers = t.getResponseHeaders();
                    headers.set("ETag", newTag);

                    int status = isOverwrite ? 200 : 201;
                    respondJson(t, status, 
                        "overwrite", isOverwrite,
                        "path", uri);
                } else if (method.equals("DELETE")) {

                    File file = new File(root + uri);

                    if (!file.exists()) {
                        respondJson(t, 404,
                            "error", "File not found",
                            "path", uri);
                    }

                    String tag = Long.toString(file.lastModified(), 36);

                    Headers requestHeaders = t.getRequestHeaders();
                    String matchTag = requestHeaders.getFirst("If-Match");

                    if (matchTag != null && !matchTag.equals(tag)) {

                        respondJson(t, 412,
                            "error", "Entity tag differs from expected",
                            "expected", tag,
                            "path", uri);
                        return;
                    }

                    file.delete();
                    respondJson(t, 200,
                        "path", uri);
                } else {

                    respondJson(t, 405,
                        "error", "Unsupported method '" + method + "'");
                }

            } else {
                respondJson(t, 400,
                    "error", "Invalid url",
                    "url", uri);
            }

        }
    }
}
