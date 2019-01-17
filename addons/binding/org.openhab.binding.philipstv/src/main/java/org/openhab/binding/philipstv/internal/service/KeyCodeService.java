/**
 * Copyright (c) 2010-2019 by the respective copyright holders.
 * <p>
 * All rights reserved. This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.philipstv.internal.service;

import org.openhab.binding.philipstv.handler.*;
import org.openhab.binding.philipstv.internal.service.model.*;

import static org.openhab.binding.philipstv.PhilipsTvBindingConstants.*;

/**
 * The {@link KeyCodeService} is responsible for handling key code commands, which emulate a button
 * press on a remote control.
 * @author Benjamin Meyer - Initial contribution
 */
public class KeyCodeService implements PhilipsTvService {

  private final Logger logger = LoggerFactory.getLogger(getClass());

  private final ConnectionService connectionService = new ConnectionService();

  @Override
  public void handleCommand(String channel, Command command, PhilipsTvHandler handler) {
    KeyCode keyCode = null;
    if (isSupportedCommand(command)) {
      // Three approaches to resolve the KEY_CODE
      try {
        keyCode = KeyCode.valueOf(command.toString().toUpperCase());
      } catch (IllegalArgumentException e) {
        try {
          keyCode = KeyCode.valueOf("KEY_" + command.toString().toUpperCase());
        } catch (IllegalArgumentException e2) {
          try {
            keyCode = KeyCode.getKeyCodeForValue(command.toString());
          } catch (IllegalArgumentException e3) {
            // do nothing, error message is logged later
          }
        }
      }

      if (keyCode != null) {
        try {
          sendKeyCode(handler.credentials, keyCode, handler.target);
        } catch (Exception e) {
          if (isTvOfflineException(e)) {
            logger.warn("Could not execute command for key code, the TV is offline.");
            handler.postUpdateThing(ThingStatus.OFFLINE, ThingStatusDetail.NONE, TV_OFFLINE_MSG);
          } else if (isTvNotListeningException(e)) {
            handler.postUpdateThing(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, TV_NOT_LISTENING_MSG);
          } else {
            logger.error("Unknown error occurred while sending keyCode code {}: {}", keyCode,
                e.getMessage(), e);
          }
        }
      } else {
        logger.warn("Command '{}' not a supported keyCode code.", command);
      }
    } else {
      if (!(command instanceof RefreshType)) { // RefreshType is valid but ignored
        logger.warn("Not a supported command: {}", command);
      }
    }
  }

  private static boolean isSupportedCommand(Command command) {
    return (command instanceof StringType) || (command instanceof NextPreviousType)
        || (command instanceof PlayPauseType) || (command instanceof RewindFastforwardType);
  }

  private void sendKeyCode(CredentialDetails credentials, KeyCode key,
      HttpHost target) throws IOException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
    JsonObject keyCodeJson = new JsonObject();
    keyCodeJson.addProperty("key", key.toString());
    logger.debug("KeyCode Json sent: {}", keyCodeJson);
    connectionService.doHttpsPost(credentials, target, KEY_CODE_PATH, keyCodeJson.toString());
  }
}
