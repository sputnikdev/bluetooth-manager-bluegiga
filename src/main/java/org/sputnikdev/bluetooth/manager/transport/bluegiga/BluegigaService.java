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

import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.transport.Characteristic;
import org.sputnikdev.bluetooth.manager.transport.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Bluegiga transport service.
 * @author Vlad Kolotov
 */
class BluegigaService implements Service {

    private final URL url;
    private final int handleStart;
    private final int handleEnd;
    private final Map<URL, BluegigaCharacteristic> characteristics = new HashMap<>();

    BluegigaService(URL url, int handleStart, int handleEnd) {
        this.url = url;
        this.handleStart = handleStart;
        this.handleEnd = handleEnd;
    }

    @Override
    public List<Characteristic> getCharacteristics() {
        return new ArrayList<>(characteristics.values());
    }

    @Override
    public URL getURL() {
        return url;
    }

    protected void addCharacteristic(BluegigaCharacteristic characteristic) {
        synchronized (characteristics) {
            characteristics.put(characteristic.getURL(), characteristic);
        }
    }

    protected BluegigaCharacteristic getCharacteristic(URL url) {
        synchronized (characteristics) {
            return characteristics.get(url.getCharacteristicURL());
        }
    }

    protected BluegigaCharacteristic findCharacteristicByShortUUID(String shortUUID) {
        synchronized (characteristics) {
            return characteristics.values().stream()
                    .filter(characteristic -> match(characteristic, shortUUID))
                    .findFirst().orElse(null);
        }
    }

    protected int getHandleStart() {
        return handleStart;
    }

    protected int getHandleEnd() {
        return handleEnd;
    }

    private static boolean match(BluegigaCharacteristic characteristic, String shortUUID) {
        return characteristic.getURL().getCharacteristicUUID().substring(0, 8).contains(shortUUID.toLowerCase());
    }
}
