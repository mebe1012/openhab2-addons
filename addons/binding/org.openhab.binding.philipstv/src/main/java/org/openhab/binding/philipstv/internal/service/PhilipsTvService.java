/**
 * Copyright (c) 2010-2019 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.philipstv.internal.service;

import org.apache.http.conn.*;
import org.eclipse.smarthome.core.types.*;
import org.openhab.binding.philipstv.handler.*;

import java.net.*;

/**
 * Interface for Philips TV services.
 *
 * @author Benjamin Meyer - Initial contribution
 */
public interface PhilipsTvService {

    /**
     * Procedure for sending command.
     *
     * @param channel the channel to which the command applies
     * @param command the command to be handled
     */
    void handleCommand(String channel, Command command, PhilipsTvHandler handler);

    default boolean isTvOfflineException(Exception exception) {
        if((exception instanceof NoRouteToHostException) && exception.getMessage().contains("Host unreachable")) {
            return true;
        } else
            return (exception instanceof ConnectTimeoutException) && exception.getMessage().contains("timed out");
    }

    default boolean isTvNotListeningException(Exception exception) {
        return (exception instanceof HttpHostConnectException) && exception.getMessage().contains("Connection refused");
    }
}
