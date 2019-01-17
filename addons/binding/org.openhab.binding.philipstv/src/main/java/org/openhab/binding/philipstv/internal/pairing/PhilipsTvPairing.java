/**
 * Copyright (c) 2010-2019 by the respective copyright holders.
 * <p>
 * All rights reserved. This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.philipstv.internal.pairing;

import com.google.gson.*;
import org.apache.http.*;
import org.apache.http.auth.*;
import org.apache.http.client.*;
import org.apache.http.client.methods.*;
import org.apache.http.client.protocol.*;
import org.apache.http.entity.*;
import org.apache.http.impl.auth.*;
import org.apache.http.impl.client.*;
import org.apache.http.util.*;
import org.openhab.binding.philipstv.*;
import org.openhab.binding.philipstv.handler.*;
import org.openhab.binding.philipstv.internal.service.*;
import org.openhab.binding.philipstv.internal.service.model.*;
import org.slf4j.*;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;

import static org.openhab.binding.philipstv.PhilipsTvBindingConstants.*;

/**
 * The {@link PhilipsTvPairing} is responsible for the initial pairing process with the Philips TV.
 * The outcome of this one-time pairing is a registered user with password, which will be used for
 * controlling the tv.
 * @author Benjamin Meyer - Initial contribution
 */
public class PhilipsTvPairing {

  private final Logger logger = LoggerFactory.getLogger(getClass());

  public static String authTimestamp;

  public static String authKey;

  public static String deviceId;

  private final String pairingBasePath = BASE_PATH + "pair" + SLASH;

  private final String requestPairingCodePath = pairingBasePath + "request";

  private final String grantPairingCodePath = pairingBasePath + "grant";

  private final ConnectionService connectionService = new ConnectionService();

  public void requestPairingCode(HttpHost target) throws IOException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException {

    JsonObject requestCodeJson = new JsonObject();

    JsonArray scopeJson = new JsonArray();
    scopeJson.add("read");
    scopeJson.add("write");
    scopeJson.add("control");

    requestCodeJson.add("scope", scopeJson);
    requestCodeJson.add("device", createDeviceSpecification());

    String jsonContent = connectionService.doHttpsPost(CredentialDetails.ofUsernameAndPassword("", ""), target, requestPairingCodePath, requestCodeJson.toString());

    JsonObject jsonObject = new JsonParser().parse(jsonContent).getAsJsonObject();
    authTimestamp = jsonObject.get("timestamp").getAsString();
    authKey = jsonObject.get("auth_key").getAsString();

    logger.info("The pairing code is valid for {} seconds.", jsonObject.get("timeout").getAsString());
  }

  public void finishPairingWithTv(PhilipsTvHandler handler) throws NoSuchAlgorithmException, InvalidKeyException, IOException, MalformedChallengeException, KeyStoreException, KeyManagementException, AuthenticationException {
    String pairingCode = handler.config.pairingCode;
    JsonObject grantPairingJson = new JsonObject();

    JsonObject authJson = new JsonObject();
    authJson.addProperty("auth_signature", calculateRFC2104HMAC(authTimestamp + pairingCode));
    authJson.addProperty("auth_AppId", "1");
    authJson.addProperty("auth_timestamp", Integer.valueOf(authTimestamp));
    authJson.addProperty("pin", pairingCode);
    grantPairingJson.add("device", createDeviceSpecification());
    grantPairingJson.add("auth", authJson);

    try (CloseableHttpClient client = ConnectionUtil.createClientWithCredentials(handler.target, CredentialDetails.ofUsernameAndPassword(deviceId, authKey))) {
      logger.debug("{} and device id: {} and auth_key: {}", grantPairingJson, deviceId, authKey);

      HttpPost httpPost = new HttpPost(grantPairingCodePath);
      httpPost.setHeader("Content-type", "application/json");
      httpPost.setEntity(new StringEntity(grantPairingJson.toString()));

      DigestScheme digestAuth = new DigestScheme();
      // Prevent ERROR logging from HttpAuthenticator
      digestAuth.overrideParamter("realm", "unknown");
      digestAuth.overrideParamter("nonce", "unknown");

      AuthCache authCache = new BasicAuthCache();
      authCache.put(handler.target, digestAuth);

      HttpClientContext localContext = HttpClientContext.create();
      localContext.setAuthCache(authCache);

      try (CloseableHttpResponse response = client.execute(handler.target, httpPost, localContext)) {
        digestAuth.processChallenge(response.getFirstHeader("WWW-Authenticate"));
        String jsonContent = EntityUtils.toString(response.getEntity());
        logger.debug("----------------------------------------");
        logger.debug("{}", response.getStatusLine());
        logger.debug("{}", jsonContent);
      }

      Header authenticate = digestAuth.authenticate(new UsernamePasswordCredentials(deviceId, authKey), httpPost,
          localContext);
      httpPost.addHeader(authenticate);

      String jsonContent = "";
      try (CloseableHttpResponse response = client
          .execute(handler.target, httpPost, localContext)) {
        jsonContent = EntityUtils.toString(response.getEntity());
      }

      JsonObject jsonObject = new JsonParser().parse(jsonContent).getAsJsonObject();
      logger.debug("Json fetched as result: {}", jsonObject);
      handler.config.username = deviceId;
      handler.getThing().getConfiguration().put(USERNAME, deviceId);
      handler.config.password = authKey;
      handler.getThing().getConfiguration().put(PASSWORD, authKey);
    }
  }

  private String createDeviceId() {
    StringBuilder deviceIdBuilder = new StringBuilder();
     String chars = "abcdefghkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ123456789";
     for (int i = 0; i < 16; i++) {
     int index = (int) Math.floor(Math.random() * chars.length());
       deviceIdBuilder.append(chars.charAt(index));
     }
     return deviceIdBuilder.toString();
  }

  private JsonObject createDeviceSpecification() {
    JsonObject deviceJson = new JsonObject();
    deviceJson.addProperty("app_name", "ApplicationName");
    deviceJson.addProperty("app_id", "app.id");
    deviceJson.addProperty("device_name", "heliotrope");
    deviceJson.addProperty("device_os", "Android");
    deviceJson.addProperty("type", "native");
    if(deviceId == null) {
      deviceId = createDeviceId();
    }
    deviceJson.addProperty("id", deviceId);
    return deviceJson;
  }

  private String toHexString(byte[] bytes) {
    try (Formatter formatter = new Formatter()) {
      for (byte b : bytes) {
        formatter.format("%02x", b);
      }

      return formatter.toString();
    }
  }

  private String calculateRFC2104HMAC(String data)
      throws NoSuchAlgorithmException, InvalidKeyException, UnsupportedEncodingException {
    String hmacSHA1 = "HmacSHA1";
    // Key used for generated the HMAC signature
    String secretKey = "ZmVay1EQVFOaZhwQ4Kv81ypLAZNczV9sG4KkseXWn1NEk6cXmPKO/MCa9sryslvLCFMnNe4Z4CPXzToowvhHvA==";
    Key signingKey = new SecretKeySpec(Base64.getDecoder().decode(secretKey), hmacSHA1);
    Mac mac = Mac.getInstance(hmacSHA1);
    mac.init(signingKey);
    return Base64.getEncoder().encodeToString(toHexString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8.name()))).getBytes(StandardCharsets.UTF_8.name()));
  }
}
