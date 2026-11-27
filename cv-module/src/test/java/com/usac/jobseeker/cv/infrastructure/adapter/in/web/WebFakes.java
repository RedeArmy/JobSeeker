package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;

/**
 * Dobles mínimos de la web para probar los controladores sin levantar Spring: un archivo subido y
 * una respuesta que solo recuerda su estado y sus cabeceras. `cv` no depende de `spring-test`.
 */
final class WebFakes {

  private WebFakes() {}

  /** Un archivo subido en memoria; con `failing` su lectura falla como una conexión cortada. */
  static MultipartFile file(String name, byte[] content, boolean failing) {
    return new MultipartFile() {
      @Override
      public String getName() {
        return "file";
      }

      @Override
      public String getOriginalFilename() {
        return name;
      }

      @Override
      public String getContentType() {
        return "application/octet-stream";
      }

      @Override
      public boolean isEmpty() {
        return content.length == 0;
      }

      @Override
      public long getSize() {
        return content.length;
      }

      @Override
      public byte[] getBytes() throws IOException {
        if (failing) {
          throw new IOException("conexión cortada");
        }
        return content.clone();
      }

      @Override
      public InputStream getInputStream() {
        return new ByteArrayInputStream(content);
      }

      @Override
      public void transferTo(File destination) {
        throw new UnsupportedOperationException("no se guarda en disco");
      }
    };
  }

  static MultipartFile file(String name, byte[] content) {
    return file(name, content, false);
  }

  /** Lo que el controlador fijó en la respuesta. */
  static final class Response {
    int status = 200;
    final Map<String, String> headers = new HashMap<>();

    HttpServletResponse servlet() {
      return (HttpServletResponse)
          Proxy.newProxyInstance(
              HttpServletResponse.class.getClassLoader(),
              new Class<?>[] {HttpServletResponse.class},
              (proxy, method, args) -> {
                switch (method.getName()) {
                  case "setStatus" -> status = (int) args[0];
                  case "setHeader" -> headers.put((String) args[0], (String) args[1]);
                  default -> throw new UnsupportedOperationException(method.getName());
                }
                return null;
              });
    }
  }
}
