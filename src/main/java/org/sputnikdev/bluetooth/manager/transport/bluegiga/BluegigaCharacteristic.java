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

import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.transport.Notification;
import org.sputnikdev.bluetooth.manager.transport.Characteristic;

/**
 *
 * @author Vlad Kolotov
 * @author Chris Jackson
 */
class BluegigaCharacteristic implements Characteristic {

    private final URL url;
    private final int handle;

    BluegigaCharacteristic(URL url, int handle) {
        this.url = url;
        this.handle = handle;
    }

    @Override
    public String[] getFlags() {
        return new String[0];
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
}
