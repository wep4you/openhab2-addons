/**
 * Copyright (c) 2014-2016 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.netatmo.handler;

import static org.openhab.binding.netatmo.NetatmoBindingConstants.*;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.PointType;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusInfo;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.types.State;
import org.openhab.binding.netatmo.config.NetatmoDeviceConfiguration;
import org.openhab.binding.netatmo.config.NetatmoModuleConfiguration;
import org.openhab.binding.netatmo.internal.NADeviceAdapter;
import org.openhab.binding.netatmo.internal.NAModuleAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.client.model.NAPlace;

/**
 * {@link NetatmoDeviceHandler} is the handler for a given
 * device accessed through the Netatmo Bridge
 *
 * @author Gaël L'hopital - Initial contribution OH2 version
 *
 */
public abstract class NetatmoDeviceHandler<X extends NetatmoDeviceConfiguration>
        extends AbstractNetatmoThingHandler<X> {

    protected NADeviceAdapter<?> device;
    private static Logger logger = LoggerFactory.getLogger(NetatmoDeviceHandler.class);
    private ScheduledFuture<?> refreshJob;

    public NetatmoDeviceHandler(Thing thing, Class<X> configurationClass) {
        super(thing, configurationClass);
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        super.bridgeStatusChanged(bridgeStatusInfo);
        if (bridgeStatusInfo.getStatus() == ThingStatus.ONLINE) {
            refreshJob = scheduler.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    updateChannels(getConfiguration().getEquipmentId());
                }
            }, 1, getConfiguration().refreshInterval, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void dispose() {
        if (refreshJob != null && !refreshJob.isCancelled()) {
            refreshJob.cancel(true);
            refreshJob = null;
        }
    }

    abstract protected NADeviceAdapter<?> updateReadings(NetatmoBridgeHandler bridgeHandler, String equipmentId);

    @Override
    protected void updateChannels(String equipmentId) {
        NetatmoBridgeHandler bridgeHandler = (NetatmoBridgeHandler) getBridge().getHandler();
        try {
            device = updateReadings(bridgeHandler, equipmentId);
            if (device != null) {
                super.updateChannels(equipmentId);
                updateChildModules(bridgeHandler, equipmentId);
            }
        } catch (Exception e) {
            logger.error("Exception when trying to update channels: {}", e.getMessage());
        }
    }

    @Override
    protected State getNAThingProperty(String channelId) {
        switch (channelId) {
            case CHANNEL_LAST_STATUS_STORE:
                return toDateTimeType(device.getLastStatusStore());
            case CHANNEL_LOCATION:
                NAPlace place = device.getPlace();
                return new PointType(new DecimalType(place.getLocation().get(1)),
                        new DecimalType(place.getLocation().get(0)), new DecimalType(place.getAltitude()));
            case CHANNEL_WIFI_STATUS:
                Integer wifiStatus = device.getWifiStatus();
                return new DecimalType(getSignalStrength(wifiStatus));
            case CHANNEL_UNIT:
                return new DecimalType(device.getUserAdministrative().getUnit());
            default:
                return super.getNAThingProperty(channelId);
        }
    }

    private void updateChildModules(NetatmoBridgeHandler bridgeHandler, String equipmentId) {
        for (Thing handler : getBridge().getThings()) {
            ThingHandler thingHandler = handler.getHandler();
            if (thingHandler instanceof NetatmoModuleHandler) {
                @SuppressWarnings("unchecked")
                NetatmoModuleHandler<NetatmoModuleConfiguration> moduleHandler = (NetatmoModuleHandler<NetatmoModuleConfiguration>) thingHandler;
                NetatmoModuleConfiguration moduleConfiguration = moduleHandler.getConfiguration();
                String parentId = moduleConfiguration.getParentId();
                if (equipmentId.equalsIgnoreCase(parentId)) {
                    String childId = moduleHandler.getConfiguration().getEquipmentId();
                    NAModuleAdapter module = device.getModules().get(childId);
                    moduleHandler.updateChannels(bridgeHandler, module);
                }
            }
        }
    }

}
