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
import org.sputnikdev.bluetooth.manager.transport.Descriptor;

import java.util.UUID;

/**
 * Bluegiga transport descriptor.
 * @author Vlad Kolotov
 */
class BluegigaDescriptor implements Descriptor {

    private final Logger logger = LoggerFactory.getLogger(BluegigaDescriptor.class);

    private final int connectionHandle;
    private final int descriptorHandle;
    private final UUID uuid;
    private final BluegigaHandler bgHandler;

    BluegigaDescriptor(BluegigaHandler bgHandler, int connectionHandle, int descriptorHandle, UUID uuid) {
        this.bgHandler = bgHandler;
        this.connectionHandle = connectionHandle;
        this.descriptorHandle = descriptorHandle;
        this.uuid = uuid;
    }

    @Override
    public byte[] readValue() {
        logger.debug("Reading value: {} : {}", connectionHandle, descriptorHandle);
        BlueGigaAttributeValueEvent blueGigaAttributeValueEvent =
                bgHandler.readCharacteristic(connectionHandle, descriptorHandle);
        return BluegigaUtils.fromInts(blueGigaAttributeValueEvent.getValue());
    }

    @Override
    public boolean writeValue(byte[] bytes) {
        logger.debug("Writing value: {} : {}", connectionHandle, descriptorHandle);
        int[] data = BluegigaUtils.fromBytes(bytes);
        BlueGigaProcedureCompletedEvent event = bgHandler.writeCharacteristic(connectionHandle, descriptorHandle, data);
        if (event.getResult() != BgApiResponse.SUCCESS) {
            logger.warn("Write operation failed for {}/{} descriptor. Response: {}.",
                    connectionHandle, descriptorHandle, event.getResult());
            return false;
        }
        return true;
    }

    int getConnectionHandle() {
        return connectionHandle;
    }

    int getDescriptorHandle() {
        return descriptorHandle;
    }

    UUID getUuid() {
        return uuid;
    }

}
