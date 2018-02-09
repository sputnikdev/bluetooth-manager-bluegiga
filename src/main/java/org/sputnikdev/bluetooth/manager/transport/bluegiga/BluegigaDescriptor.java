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

import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaAttributeValueEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaProcedureCompletedEvent;
import com.zsmartsystems.bluetooth.bluegiga.enumeration.BgApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.transport.Descriptor;

/**
 * Bluegiga transport descriptor.
 * @author Vlad Kolotov
 */
class BluegigaDescriptor implements Descriptor {

    private final Logger logger = LoggerFactory.getLogger(BluegigaDescriptor.class);

    private final URL url;
    private final int connectionHandle;
    private final int descriptorHandle;
    private final BluegigaHandler bgHandler;

    BluegigaDescriptor(BluegigaHandler bgHandler, URL url, int connectionHandle, int descriptorHandle) {
        this.bgHandler = bgHandler;
        this.url = url;
        this.connectionHandle = connectionHandle;
        this.descriptorHandle = descriptorHandle;
    }

    @Override
    public byte[] readValue() {
        logger.debug("Reading value: {}", url);
        BlueGigaAttributeValueEvent blueGigaAttributeValueEvent =
                bgHandler.readCharacteristic(connectionHandle, descriptorHandle);
        return BluegigaUtils.fromInts(blueGigaAttributeValueEvent.getValue());
    }

    @Override
    public boolean writeValue(byte[] bytes) {
        logger.debug("Writing value: {}", url);
        int[] data = BluegigaUtils.fromBytes(bytes);
        BlueGigaProcedureCompletedEvent event = bgHandler.writeCharacteristic(connectionHandle, descriptorHandle, data);
        if (event.getResult() != BgApiResponse.SUCCESS) {
            logger.warn("Write operation failed for {} descriptor. Response: {}.", url, event.getResult());
            return false;
        }
        return true;
    }

    @Override
    public URL getURL() {
        return url;
    }

    @Override
    public void dispose() {
        // do nothing
    }

}
