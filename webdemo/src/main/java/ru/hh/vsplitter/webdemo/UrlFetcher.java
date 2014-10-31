package ru.hh.vsplitter.webdemo;

import com.google.common.base.Throwables;
import org.springframework.stereotype.Service;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

@Service
public class UrlFetcher {

  private static final int CONNECT_TIMEOUT_MILLIS = 5000;
  private static final int TIMEOUT_MILLIS = 10000;

  public String fetchUrl(String urlLocation) {
    URL url;
    HttpURLConnection conn;
    BufferedReader rd;
    String line;
    String result = "";
    try {
      url = new URL(urlLocation);
      conn = (HttpURLConnection) url.openConnection();
      conn.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
      conn.setReadTimeout(TIMEOUT_MILLIS);
      conn.setRequestMethod("GET");
      rd = new BufferedReader(new InputStreamReader(conn.getInputStream()));
      while ((line = rd.readLine()) != null) {
        result += line;
      }
      rd.close();
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
    return result;
  }

}
