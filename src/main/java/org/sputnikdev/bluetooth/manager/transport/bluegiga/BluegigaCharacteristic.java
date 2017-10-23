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
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.transport.Characteristic;
import org.sputnikdev.bluetooth.manager.transport.CharacteristicAccessType;
import org.sputnikdev.bluetooth.manager.transport.Descriptor;
import org.sputnikdev.bluetooth.manager.transport.Notification;

import java.util.*;

/**
 *
 * @author Vlad Kolotov
 * @author Chris Jackson
 */
class BluegigaCharacteristic implements Characteristic, BlueGigaEventListener {

    private final Logger logger = LoggerFactory.getLogger(BluegigaCharacteristic.class);
    private final URL url;
    private final int connectionHandle;
    private final int characteristicHandle;
    private final BluegigaHandler bgHandler;
    private Set<CharacteristicAccessType> flags = new HashSet<>();
    private Notification<byte[]> valueNotification;
    private final Map<URL, BluegigaDescriptor> descriptors = new HashMap<>();

    BluegigaCharacteristic(BluegigaHandler bgHandler, URL url, int connectionHandle, int characteristicHandle) {
        this.bgHandler = bgHandler;
        this.url = url;
        this.connectionHandle = connectionHandle;
        this.characteristicHandle = characteristicHandle;
        this.bgHandler.addEventListener(this);
    }

    @Override
    public Set<CharacteristicAccessType> getFlags() {
        return flags;
    }

    void setFlags(Set<CharacteristicAccessType> flags) {
        this.flags = flags;
    }

    @Override
    public boolean isNotifying() {
        if (flags.contains(CharacteristicAccessType.NOTIFY) || flags.contains(CharacteristicAccessType.INDICATE)) {
            byte[] configurationData = getConfiguration();
            if (configurationData != null && configurationData.length > 0) {
                return (configurationData[0] & 0x3) > 0;
            }
        }
        return false;
    }

    @Override
    public byte[] readValue() {
        BlueGigaAttributeValueEvent blueGigaAttributeValueEvent =
                bgHandler.readCharacteristic(connectionHandle, characteristicHandle);
        return BluegigaUtils.fromInts(blueGigaAttributeValueEvent.getValue());
    }

    @Override
    public boolean writeValue(byte[] bytes) {
        int[] data = BluegigaUtils.fromBytes(bytes);
        if (flags.contains(CharacteristicAccessType.WRITE_WITHOUT_RESPONSE)) {
            return bgHandler.writeCharacteristicWithoutResponse(connectionHandle, characteristicHandle, data);
        } else {
            return bgHandler.writeCharacteristic(connectionHandle, characteristicHandle, data)
                    .getResult() == BgApiResponse.SUCCESS;
        }
    }

    @Override
    public void enableValueNotifications(Notification<byte[]> notification) {
        toggleNotification(true);
        this.valueNotification = notification;
    }

    @Override
    public void disableValueNotifications() {
        toggleNotification(false);
        this.valueNotification = null;
    }

    @Override
    public URL getURL() {
        return url;
    }

    @Override
    public void dispose() { }

    int getCharacteristicHandle() {
        return characteristicHandle;
    }

    @Override
    public void bluegigaEventReceived(BlueGigaResponse event) {
        Notification<byte[]> notification = valueNotification;
        if (notification != null && event instanceof BlueGigaAttributeValueEvent) {
            BlueGigaAttributeValueEvent attributeValueEvent = (BlueGigaAttributeValueEvent) event;
            if (attributeValueEvent.getConnection() == connectionHandle
                && attributeValueEvent.getAttHandle() == characteristicHandle) {
                notification.notify(BluegigaUtils.fromInts(attributeValueEvent.getValue()));
            }
        }
    }

    void addDescriptor(BluegigaDescriptor descriptor) {
        synchronized (descriptors) {
            descriptors.put(descriptor.getURL(), descriptor);
        }
    }

    Set<BluegigaDescriptor> getDescriptors() {
        synchronized (descriptors) {
            return new HashSet<>(descriptors.values());
        }
    }

    private void toggleNotification(boolean enabled) {
        if (!(flags.contains(CharacteristicAccessType.NOTIFY) || flags.contains(CharacteristicAccessType.INDICATE))) {
            logger.error("The characteristic {} does not support neither notifications nor indications", url);
            return;
        }
        BluegigaDescriptor configuration = getConfigurationDescriptor();

        if (configuration == null) {
            logger.error("Could not subscribe to a notification, "
                + "because configuration descriptor was not found: {}", url);
            return;
        }

        byte[] config;

        if (flags.contains(CharacteristicAccessType.NOTIFY)) {
            config = enabled ? new byte[] {0x1} : new byte[] {0x0};
        } else {
            config = enabled ? new byte[] {0x2} : new byte[] {0x0};
        }
        if (!configuration.writeValue(config)) {
            throw new BluegigaException("Could not configure characteristic (enable/disable) notification: " + enabled);
        }
    }

    private byte[] getConfiguration() {
        BluegigaDescriptor configuration = getConfigurationDescriptor();
        if (configuration != null) {
            return configuration.readValue();
        }
        return null;
    }

    private BluegigaDescriptor getConfigurationDescriptor() {
        return descriptors.get(url.copyWithCharacteristic("00002902-0000-1000-8000-00805f9b34fb"));
    }
}
