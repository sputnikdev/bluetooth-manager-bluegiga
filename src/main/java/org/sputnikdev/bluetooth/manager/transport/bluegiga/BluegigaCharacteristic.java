package org.sputnikdev.bluetooth.manager.transport.bluegiga;

/*-
 * #%L
 * org.sputnikdev:bluetooth-manager-bluegiga
 * %%
 * Copyright (C) 2017 Sputnik Dev
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.zsmartsystems.bluetooth.bluegiga.BlueGigaEventListener;
import com.zsmartsystems.bluetooth.bluegiga.BlueGigaResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaAttributeValueEvent;
import com.zsmartsystems.bluetooth.bluegiga.enumeration.BgApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sputnikdev.bluetooth.DataConversionUtils;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.transport.Characteristic;
import org.sputnikdev.bluetooth.manager.transport.CharacteristicAccessType;
import org.sputnikdev.bluetooth.manager.transport.Notification;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Bluegiga transport characteristic.
 * @author Vlad Kolotov
 */
class BluegigaCharacteristic implements Characteristic, BlueGigaEventListener {

    private static final byte NOTIFYING_FLAG = 0b01;
    private static final byte INDICATING_FLAG = 0b10;
    private static final UUID CONFIGURATION_UUID = UUID.fromString("00002902-0000-0000-0000-000000000000");

    private final Logger logger = LoggerFactory.getLogger(BluegigaCharacteristic.class);
    private final URL url;
    private final int connectionHandle;
    private final int characteristicHandle;
    private final BluegigaHandler bgHandler;
    private Set<CharacteristicAccessType> flags = new HashSet<>();
    private Notification<byte[]> valueNotification;
    private final Map<UUID, BluegigaDescriptor> descriptors = new HashMap<>();

    protected BluegigaCharacteristic(BluegigaHandler bgHandler, URL url,
                                     int connectionHandle, int characteristicHandle) {
        this.bgHandler = bgHandler;
        this.url = url;
        this.connectionHandle = connectionHandle;
        this.characteristicHandle = characteristicHandle;
    }

    @Override
    public Set<CharacteristicAccessType> getFlags() {
        return Collections.unmodifiableSet(flags);
    }

    @Override
    public boolean isNotifying() {
        if (flags.contains(CharacteristicAccessType.NOTIFY) || flags.contains(CharacteristicAccessType.INDICATE)) {
            byte[] configurationData = getConfiguration();
            if (configurationData != null && configurationData.length > 0) {
                return (configurationData[0] & (NOTIFYING_FLAG | INDICATING_FLAG)) > 0;
            } else {
                // it appears that some characteristics with notify flag do not have any configuration descriptors,
                // but still can notify, this basically means that notification is enabled by default
                // and cannot be disabled
                return true;
            }
        }
        return false;
    }

    @Override
    public byte[] readValue() {
        logger.debug("Reading value: {}", url);
        BlueGigaAttributeValueEvent blueGigaAttributeValueEvent =
                bgHandler.readCharacteristic(connectionHandle, characteristicHandle);
        byte[] data = BluegigaUtils.fromInts(blueGigaAttributeValueEvent.getValue());
        if (logger.isTraceEnabled()) {
            logger.trace("Value read: {} : {}", url, DataConversionUtils.convert(data, 16));
        }
        return data;
    }

    @Override
    public boolean writeValue(byte[] bytes) {
        logger.debug("Writing value: {}", url);
        int[] data = BluegigaUtils.fromBytes(bytes);
        if (flags.contains(CharacteristicAccessType.WRITE)) {
            return bgHandler.writeCharacteristic(connectionHandle, characteristicHandle, data)
                    .getResult() == BgApiResponse.SUCCESS;
        } else {
            return bgHandler.writeCharacteristicWithoutResponse(connectionHandle, characteristicHandle, data);
        }
    }

    @Override
    public void enableValueNotifications(Notification<byte[]> notification) {
        logger.debug("Enable value notifications: {}", url);
        toggleNotification(true);
        valueNotification = notification;
    }

    @Override
    public void disableValueNotifications() {
        logger.debug("Disable value notifications: {}", url);
        toggleNotification(false);
        valueNotification = null;
    }

    @Override
    public URL getURL() {
        return url;
    }

    @Override
    public void bluegigaEventReceived(BlueGigaResponse event) {
        Notification<byte[]> notification = valueNotification;
        if (notification != null && event instanceof BlueGigaAttributeValueEvent) {
            BlueGigaAttributeValueEvent attributeValueEvent = (BlueGigaAttributeValueEvent) event;
            if (attributeValueEvent.getConnection() == connectionHandle
                && attributeValueEvent.getAttHandle() == characteristicHandle) {
                byte[] data = BluegigaUtils.fromInts(attributeValueEvent.getValue());
                if (logger.isTraceEnabled()) {
                    logger.trace("Notification received: {} : {}", url, DataConversionUtils.convert(data, 16));
                }
                try {
                    notification.notify(data);
                } catch (Exception ex) {
                    logger.error("Error occurred in changed notification", ex);
                }
            }
        }
    }

    @Override
    public boolean isNotificationConfigurable() {
        return getNotificationConfigurationDescriptor() != null;
    }

    protected void setFlags(Set<CharacteristicAccessType> flags) {
        this.flags = flags;
    }

    protected int getCharacteristicHandle() {
        return characteristicHandle;
    }

    protected void addDescriptor(BluegigaDescriptor descriptor) {
        synchronized (descriptors) {
            descriptors.put(descriptor.getUuid(), descriptor);
        }
    }

    protected Set<BluegigaDescriptor> getDescriptors() {
        synchronized (descriptors) {
            return new HashSet<>(descriptors.values());
        }
    }

    protected void toggleNotification(boolean enabled) {
        logger.debug("Toggling notification: {} / {}", url, enabled);
        if (!(flags.contains(CharacteristicAccessType.NOTIFY) || flags.contains(CharacteristicAccessType.INDICATE))) {
            logger.debug("The characteristic {} does not support neither notifications nor indications; flags: {}.",
                    url, flags.stream().map(Enum::toString).collect(Collectors.joining(", ")));
            return;
        }
        BluegigaDescriptor configuration = getNotificationConfigurationDescriptor();

        if (configuration == null) {
            // it appears that some characteristics with notify flag do not have any configuration descriptors,
            // but still can notify, this basically means that notification is enabled by default
            // and cannot be disabled
            return;
        }

        byte[] config = {0x00, 0x00};

        if (enabled) {
            if (flags.contains(CharacteristicAccessType.NOTIFY)) {
                config = new byte[] {NOTIFYING_FLAG, 0x00};
            } else if (flags.contains(CharacteristicAccessType.INDICATE)) {
                config = new byte[] {INDICATING_FLAG, 0x00};
            }
        }

        if (enabled) {
            bgHandler.addEventListener(this);
        } else {
            bgHandler.removeEventListener(this);
        }

        if (!configuration.writeValue(config)) {
            throw new BluegigaException("Could not configure characteristic (enable/disable) notification: " + enabled);
        }
    }

    protected void dispose() {
        descriptors.clear();
        bgHandler.removeEventListener(this);
    }

    private byte[] getConfiguration() {
        BluegigaDescriptor configuration = getNotificationConfigurationDescriptor();
        if (configuration != null) {
            return configuration.readValue();
        }
        return null;
    }

    private BluegigaDescriptor getNotificationConfigurationDescriptor() {
        return descriptors.get(CONFIGURATION_UUID);
    }
}
