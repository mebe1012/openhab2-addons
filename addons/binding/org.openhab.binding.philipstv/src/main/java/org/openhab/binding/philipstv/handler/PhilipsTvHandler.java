/**
 * Copyright (c) 2010-2019 by the respective copyright holders.
 * <p>
 * All rights reserved. This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.philipstv.handler;

import org.apache.http.*;
import org.eclipse.smarthome.config.discovery.*;
import org.eclipse.smarthome.core.library.types.*;
import org.eclipse.smarthome.core.thing.*;
import org.eclipse.smarthome.core.thing.binding.*;
import org.eclipse.smarthome.core.types.*;
import org.openhab.binding.philipstv.*;
import org.openhab.binding.philipstv.internal.config.*;
import org.openhab.binding.philipstv.internal.pairing.*;
import org.openhab.binding.philipstv.internal.service.*;
import org.openhab.binding.philipstv.internal.service.model.*;
import org.slf4j.*;

import java.io.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

import static org.openhab.binding.philipstv.PhilipsTvBindingConstants.*;

/**
 * The {@link PhilipsTvHandler} is responsible for handling commands, which are sent to one of the
 * channels.
 * @author Benjamin Meyer - Initial contribution
 */
public class PhilipsTvHandler extends BaseThingHandler implements DiscoveryListener {

  private final Logger logger = LoggerFactory.getLogger(getClass());

  private DiscoveryServiceRegistry discoveryServiceRegistry;

  private PhilipsTvDynamicStateDescriptionProvider stateDescriptionProvider;

  public HttpHost target;

  public PhilipsTvConfiguration config;

  public CredentialDetails credentials;

  private ThingUID upnpThingUID;

  private ScheduledFuture<?> refreshHandler;

  /* Philips TV services */
  private final Map<String, PhilipsTvService> channelServices;

  public PhilipsTvHandler(Thing thing, DiscoveryServiceRegistry discoveryServiceRegistry,
      PhilipsTvDynamicStateDescriptionProvider stateDescriptionProvider) {
    super(thing);

    logger.debug("Create a Philips TV Handler for thing '{}'", thing.getUID());

    if (discoveryServiceRegistry != null) {
      logger.debug("Discovery servic registry was initialized.");
      this.discoveryServiceRegistry = discoveryServiceRegistry;
    }

    if (stateDescriptionProvider != null) {
      logger.debug("State description was initialized.");
      this.stateDescriptionProvider = stateDescriptionProvider;
    }

    Map<String, PhilipsTvService> services = new HashMap<>();

    PhilipsTvService volumeService = new VolumeService();
    services.put(CHANNEL_VOLUME, volumeService);
    services.put(CHANNEL_MUTE, volumeService);

    PhilipsTvService keyCodeService = new KeyCodeService();
    services.put(CHANNEL_KEY_CODE, keyCodeService);
    services.put(CHANNEL_PLAYER, keyCodeService);

    PhilipsTvService appService = new AppService();
    services.put(CHANNEL_APP_NAME, appService);
    services.put(CHANNEL_APP_ICON, appService);

    PhilipsTvService ambilightService = new AmbilightService();
    services.put(CHANNEL_AMBILIGHT_POWER, ambilightService);
    services.put(CHANNEL_AMBILIGHT_HUE_POWER, ambilightService);

    services.put(CHANNEL_TV_CHANNEL, new TvChannelService());
    services.put(CHANNEL_POWER, new PowerService());
    services.put(CHANNEL_SEARCH_CONTENT, new SearchContentService());
    channelServices = Collections.unmodifiableMap(services);
  }

  @Override
  public void handleCommand(ChannelUID channelUID, Command command) {
    logger.debug("Received channel: {}, command: {}", channelUID, command);

    if((config.username == null) || (config.password == null)) {
      return; // pairing process is not finished
    }

    if ((getThing().getStatus() == ThingStatus.OFFLINE) && (!channelUID.getId().equals(CHANNEL_POWER))) {
      // Check if tv turned on meanwhile
      channelServices.get(CHANNEL_POWER).handleCommand(CHANNEL_POWER, RefreshType.REFRESH, this);
      if (getThing().getStatus() == ThingStatus.OFFLINE) {
        // still offline
        logger.info("Cannot execute command {} for channel {}: PowerState of TV was checked and resolved to offline.", command, channelUID.getId());
        return;
      }
    }

    String channel = channelUID.getId();
    long startTime = System.currentTimeMillis();
    // Delegate the other commands to correct channel service
    PhilipsTvService philipsTvService = channelServices.get(channel);

    if (philipsTvService == null) {
      logger.warn("Unknown channel for Philips TV Binding: {}", channel);
      return;
    }

    philipsTvService.handleCommand(channel, command, this);
    long stopTime = System.currentTimeMillis();
    long elapsedTime = stopTime - startTime;
    logger.trace("The command {} took : {} nanoseconds", command.toFullString(), elapsedTime);
  }

  @Override
  public void initialize() {
    logger.debug("Init of handler for Thing: {}", getThing().getLabel());
    config = getConfigAs(PhilipsTvConfiguration.class);
    if ((config.host == null) || (config.port == null)) {
      postUpdateThing(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
          "Cannot connect to Philips TV. Host and/or port are not set.");
      return;
    }

    target = new HttpHost(config.host, config.port, HTTPS);

    if ((config.pairingCode == null) && (config.username == null) && (config.password == null)) {
      updateStatus(ThingStatus.ONLINE, ThingStatusDetail.CONFIGURATION_PENDING,
          "Pairing is not configured yet, trying to present a Pairing Code on TV.");
      try {
        initPairingCodeRetrieval();
      } catch (IOException | NoSuchAlgorithmException | KeyStoreException | KeyManagementException e) {
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Error occurred while trying to present a Pairing Code on TV.");
      }
      return;
    } else if ((config.pairingCode != null) && ((config.username == null) || (config.password == null))) {
      updateStatus(ThingStatus.ONLINE, ThingStatusDetail.CONFIGURATION_PENDING,
          "Pairing Code is available, but credentials missing. Trying to retrieve them.");
      boolean hasFailed = initCredentialsRetrieval();
      if (hasFailed) {
        postUpdateThing(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
            "Error occurred during retrieval of credentials.");
        return;
      }
    }

    credentials = CredentialDetails.ofUsernameAndPassword(config.username, config.password);

    if (discoveryServiceRegistry != null) {
      discoveryServiceRegistry.addDiscoveryListener(this);
    }

    // Thing is initialized, check powerstate and available communication of the TV and set ONLINE or OFFLINE
    channelServices.get(CHANNEL_POWER).handleCommand(CHANNEL_POWER, RefreshType.REFRESH, this);
  }

  /**
   * Starts the pairing Process with the TV, which results in a Pairing Code shown on TV.
   */
  private void initPairingCodeRetrieval() throws IOException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
    logger.info("Pairing code for tv authentication is missing. "
        + "Starting initial pairing process. Please provide manually the pairing code shown on the tv at the configuration of the tv thing.");
    PhilipsTvPairing pairing = new PhilipsTvPairing();
    pairing.requestPairingCode(target);
  }

  private boolean initCredentialsRetrieval() {
    boolean hasFailed = false;
    logger.info("Pairing code is available, but username and/or password is missing. Therefore we try to grant authorization and retrieve username and password.");
    PhilipsTvPairing pairing = new PhilipsTvPairing();
    try {
      pairing.finishPairingWithTv(this);
      postUpdateThing(ThingStatus.ONLINE, ThingStatusDetail.CONFIGURATION_PENDING,
          "Authentication with Philips TV device was successful. Continueing initialization of the tv.");
    } catch (Exception e) {
      postUpdateThing(ThingStatus.OFFLINE, ThingStatusDetail.HANDLER_INITIALIZING_ERROR,
          "Could not successfully finish pairing process with the TV.");
      logger.error("Error during finishing pairing process with the TV: {}", e.getMessage(), e);
      hasFailed = true;
    }
    return hasFailed;
  }

  // callback methods for channel services
  public void postUpdateChannel(String channelUID, State state) {
    updateState(channelUID, state);
  }

  public void postUpdateThing(ThingStatus status, ThingStatusDetail statusDetail, String msg) {
    if (status == ThingStatus.ONLINE) {
      updateState(CHANNEL_POWER, OnOffType.ON);
      // Init refresh scheduler only, if pairing is completed
      if (isSchedulerInitializable()) {
        logger.debug("Creating Refresh Job for Philips TV: {}", getThing().getLabel());
        logger.debug("Refresh rate from thing config is: {}", config.refreshRate);
        startRefreshScheduler();
      }
    } else if (status == ThingStatus.OFFLINE) {
      updateState(CHANNEL_POWER, OnOffType.OFF);
      if (!TV_NOT_LISTENING_MSG.equals(msg)) { // avoid cancelling refresh if TV is temporarily not available
        if ((refreshHandler != null) && !refreshHandler.isCancelled()) {
          logger.debug("Stopping Refresh Job for Philips TV: {}", getThing().getLabel());
          refreshHandler.cancel(true);
          refreshHandler = null;
        }
        // Reset app list for new retrieval during next startup
        ((AppService) channelServices.get(CHANNEL_APP_NAME)).clearAvailableAppList();
        ((TvChannelService) channelServices.get(CHANNEL_TV_CHANNEL)).clearAvailableTvChannelList();
      }
    }
    updateStatus(status, statusDetail, msg);
  }

  private boolean isSchedulerInitializable() {
    return (config.username != null) && (config.password != null)
        && ((refreshHandler == null) || refreshHandler.isDone());
  }

  private void startRefreshScheduler() {
    int configuredDelayOrDefault = Optional.ofNullable(config.refreshRate).orElse(10);
    // If value equals zero, refreshing should not be scheduled
    if (configuredDelayOrDefault != 0) {
      refreshHandler = scheduler.scheduleWithFixedDelay(this::refreshTvProperties,
          10, configuredDelayOrDefault, TimeUnit.SECONDS);
    }
  }

  private void refreshTvProperties() {
    if (getThing().getStatus() == ThingStatus.OFFLINE) { // handles case if tv temporarily does not accept commands
      channelServices.get(CHANNEL_POWER).handleCommand(CHANNEL_POWER, RefreshType.REFRESH, this);
      if (getThing().getStatus() == ThingStatus.OFFLINE) {
        return; // tv is still not accepting comands
      }
    }

    if (isLinked(CHANNEL_VOLUME) || isLinked(CHANNEL_MUTE)) {
      channelServices.get(CHANNEL_VOLUME).handleCommand(CHANNEL_VOLUME, RefreshType.REFRESH, this);
    }

    if (isLinked(CHANNEL_APP_NAME)) {
      channelServices.get(CHANNEL_APP_NAME).handleCommand(CHANNEL_APP_NAME, RefreshType.REFRESH, this);
    }
  }

  public void updateChannelStateDescription(final String channelId, Map<String, String> values) {
    if (isLinked(channelId)) {
      List<StateOption> options = new ArrayList<>();
      values.forEach((key, value) -> options.add(new StateOption(key, value)));
      stateDescriptionProvider.setStateOptions(new ChannelUID(getThing().getUID(), channelId), options);
    }
  }

  @Override
  public void thingDiscovered(DiscoveryService source, DiscoveryResult result) {
    logger.debug("thingDiscovered: {}", result);

    if (config.host.equals(result.getProperties().get(HOST))) {
      /*
       * Philips TV discovery services creates thing UID from UPnP UDN.
       * When thing is generated manually, thing UID may not match UPnP UDN, so store it for later use (e.g.
       * thingRemoved).
       */
      upnpThingUID = result.getThingUID();
      logger.debug("thingDiscovered, thingUID={}, discoveredUID={}", getThing().getUID(), upnpThingUID);
      postUpdateThing(ThingStatus.ONLINE, ThingStatusDetail.NONE, "");
    }
  }

  @Override
  public void thingRemoved(DiscoveryService discoveryService, ThingUID thingUID) {
    logger.debug("thingRemoved: {}", thingUID);

    if (thingUID.equals(upnpThingUID)) {
      postUpdateThing(ThingStatus.OFFLINE, ThingStatusDetail.NONE, "");
    }
  }

  @Override
  public Collection<ThingUID> removeOlderResults(DiscoveryService discoveryService, long l,
      Collection<ThingTypeUID> collection, ThingUID thingUID) {
    return Collections.emptyList();
  }

  @Override
  public void dispose() {
    super.dispose();

    if (discoveryServiceRegistry != null) {
      discoveryServiceRegistry.removeDiscoveryListener(this);
    }

    if ((refreshHandler != null) && !refreshHandler.isCancelled()) {
      refreshHandler.cancel(true);
      refreshHandler = null;
    }
  }

}
