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

import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaFindInformationFoundEvent;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.transport.Characteristic;
import org.sputnikdev.bluetooth.manager.transport.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 *
 * @author Vlad Kolotov
 */
class BluegigaService implements Service {

    private final URL url;
    private final Map<URL, BluegigaCharacteristic> characteristics = new HashMap<>();

    BluegigaService(URL url) {
        this.url = url;
    }

    @Override
    public List<Characteristic> getCharacteristics() {
        return Collections.emptyList();
    }

    @Override
    public URL getURL() {
        return url;
    }

    @Override
    public void dispose() {
        synchronized (characteristics) {
            characteristics.clear();
        }
    }

    void handleEvent(BlueGigaFindInformationFoundEvent infoEvent) {
        // A Characteristic has been discovered

        URL characteristicURL = url.copyWithCharacteristic(infoEvent.getUuid().toString());
        BluegigaCharacteristic characteristic = new BluegigaCharacteristic(characteristicURL, infoEvent.getChrHandle());

        synchronized (characteristics) {
            characteristics.put(characteristicURL, characteristic);
        }

    }
}
