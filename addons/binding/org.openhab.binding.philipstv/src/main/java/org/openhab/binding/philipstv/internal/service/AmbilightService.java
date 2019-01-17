package org.openhab.binding.philipstv.internal.service;

import org.openhab.binding.philipstv.handler.*;
import org.openhab.binding.philipstv.internal.service.model.*;

import static org.openhab.binding.philipstv.PhilipsTvBindingConstants.*;

public class AmbilightService implements PhilipsTvService {

  public static final int AMBILIGHT_HUE_NODE_ID = 2131230774;

  private final Logger logger = LoggerFactory.getLogger(getClass());

  private final ConnectionService connectionService = new ConnectionService();

  @Override
  public void handleCommand(String channel, Command command, PhilipsTvHandler handler) {
    try {
      if (CHANNEL_AMBILIGHT_POWER.equals(channel) && (command instanceof OnOffType)) {
        setAmbilightPowerState(handler.credentials, command, handler.target);
      } else if (CHANNEL_AMBILIGHT_HUE_POWER.equals(channel) && (command instanceof OnOffType)) {
        setAmbilightHuePowerState(handler.credentials, command, handler.target);
      } else {
        logger.warn("Unknown command: {} for Channel {}", command, channel);
      }
    } catch (Exception e) {
      if (isTvOfflineException(e)) {
        handler.postUpdateThing(ThingStatus.OFFLINE, ThingStatusDetail.NONE, TV_OFFLINE_MSG);
      } else if (isTvNotListeningException(e)) {
        handler.postUpdateThing(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, TV_NOT_LISTENING_MSG);
      } else {
        logger.error("Error during handling the Ambilight command {} for Channel {}: {}", command, channel, e.getMessage(), e);
      }
    }
  }

  private void setAmbilightPowerState(CredentialDetails credentials, Command command,
      HttpHost target) throws IOException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
    JsonObject powerStateJson = new JsonObject();
    if (command.equals(OnOffType.ON)) {
      powerStateJson.addProperty("power", POWER_ON);
    } else { // OFF
      powerStateJson.addProperty("power", POWER_OFF);
    }
    connectionService.doHttpsPost(credentials, target, AMBILIGHT_POWERSTATE_PATH, powerStateJson.toString());
  }

  private void setAmbilightHuePowerState(CredentialDetails credentials, Command command,
      HttpHost target) throws IOException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
    JsonObject values = new JsonObject();
    JsonArray jsonArray = new JsonArray();
    JsonObject valueJson = new JsonObject();
    JsonObject valueContent = new JsonObject();
    valueContent.addProperty("Nodeid", AMBILIGHT_HUE_NODE_ID);
    valueContent.addProperty("Controllable", "true");
    valueContent.addProperty("Available", "true");
    JsonObject data = new JsonObject();
    if (command.equals(OnOffType.ON)) {
      data.addProperty("value", "true");
    } else { // OFF
      data.addProperty("value", "false");
    }
    valueContent.add("data", data);
    valueJson.add("value", valueContent);
    jsonArray.add(valueJson);
    values.add("values", jsonArray);
    connectionService.doHttpsPost(credentials, target, UPDATE_SETTINGS_PATH, values.toString());
  }
}