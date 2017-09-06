package org.sputnikdev.bluetooth.manager.transport.bluegiga;

/*-
 * #%L
 * org.sputnikdev:bluetooth-manager
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.transport.Characteristic;
import org.sputnikdev.bluetooth.manager.transport.CharacteristicAccessType;
import org.sputnikdev.bluetooth.manager.transport.Notification;

import java.util.HashSet;
import java.util.Set;

/**
 *
 * @author Vlad Kolotov
 * @author Chris Jackson
 */
class BluegigaCharacteristic implements Characteristic {

    private final Logger logger = LoggerFactory.getLogger(BluegigaCharacteristic.class);
    private final URL url;
    private final int connectionHandle;
    private final int characteristicHandle;
    private final BluegigaHandler bgHandler;
    private Set<CharacteristicAccessType> flags = new HashSet<>();

    BluegigaCharacteristic(BluegigaHandler bgHandler, URL url, int connectionHandle, int characteristicHandle) {
        this.bgHandler = bgHandler;
        this.url = url;
        this.connectionHandle = connectionHandle;
        this.characteristicHandle = characteristicHandle;
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
        return false;
    }

    @Override
    public void disableValueNotifications() {

    }

    @Override
    public byte[] readValue() {
        return new byte[0];
    }

    @Override
    public boolean writeValue(byte[] data) {
        return false;
    }

    @Override
    public void enableValueNotifications(Notification<byte[]> notification) {

    }

    @Override
    public URL getURL() {
        return url;
    }

    @Override
    public void dispose() {

    }

    int getCharacteristicHandle() {
        return characteristicHandle;
    }
}
