/**
 * Copyright (c) 2010-2019 by the respective copyright holders.
 * <p>
 * All rights reserved. This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.philipstv;

import org.apache.http.*;
import org.apache.http.auth.*;
import org.apache.http.client.*;
import org.apache.http.client.config.*;
import org.apache.http.conn.ssl.*;
import org.apache.http.impl.client.*;
import org.apache.http.ssl.SSLContextBuilder;
import org.openhab.binding.philipstv.internal.service.model.*;

import javax.net.ssl.*;
import java.security.*;

import static org.openhab.binding.philipstv.PhilipsTvBindingConstants.*;

/**
 * The {@link ConnectionUtil} is offering methods for connection specific processes.
 * @author Benjamin Meyer - Initial contribution
 */
public final class ConnectionUtil {

  private ConnectionUtil() {
  }

  public static CloseableHttpClient createClientWithCredentials(HttpHost target,
      CredentialDetails credentials) throws KeyManagementException, NoSuchAlgorithmException, KeyStoreException {

    CredentialsProvider credsProvider = new BasicCredentialsProvider();
    credsProvider.setCredentials(new AuthScope(target.getHostName(), target.getPort()),
        new UsernamePasswordCredentials(credentials.getUsername(), credentials.getPassword()));

    RequestConfig requestConfig = RequestConfig.custom()
        .setConnectTimeout(CONNECT_TIMEOUT)
        .setSocketTimeout(SOCKET_TIMEOUT)
        .build();

    return HttpClients.custom()
        .setSSLContext(getSslConnectionWithoutCertValidation())
        .setSSLHostnameVerifier(new NoopHostnameVerifier())
        .setDefaultCredentialsProvider(credsProvider)
        .setDefaultRequestConfig(requestConfig)
        .build();
  }

  public static SSLContext getSslConnectionWithoutCertValidation()
      throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException {
    return new SSLContextBuilder()
        .loadTrustMaterial(null, (certificate, authType) -> true).build();
  }
}
