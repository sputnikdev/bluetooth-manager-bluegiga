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

import com.zsmartsystems.bluetooth.bluegiga.BlueGigaEventListener;
import com.zsmartsystems.bluetooth.bluegiga.BlueGigaResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaScanResponseEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.system.BlueGigaGetInfoResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.transport.Adapter;
import org.sputnikdev.bluetooth.manager.transport.Device;
import org.sputnikdev.bluetooth.manager.transport.Notification;

import java.util.*;

/**
 *
 * @author Vlad Kolotov
 * @author Chris Jackson
 */
public class BluegigaAdapter implements Adapter, BlueGigaEventListener {

    private static final String BLUEGIGA_NAME = "Bluegiga";

    private final Logger logger = LoggerFactory.getLogger(BluegigaAdapter.class);

    private BlueGigaGetInfoResponse info;
    private boolean discovering;

    private final BluegigaHandler bgHandler;

    private final Map<URL, BluegigaDevice> devices = new HashMap<>();

    public BluegigaAdapter(String portName) {
        this.bgHandler = new BluegigaHandler(portName);
        this.info = bgHandler.bgGetInfo();
        this.bgHandler.addEventListener(this);
    }

    String getPortName() {
        return this.bgHandler.getPortName();
    }

    public boolean isAlive() {
        return getURL() != null && bgHandler.isAlive();
    }

    @Override
    public String getName() {
        return BLUEGIGA_NAME + Optional.ofNullable(info).map(i -> " v" + i.getMajor() + "." + i.getMinor()).orElse("");
    }

    @Override
    public String getAlias() {
        return null;
    }

    @Override
    public void setAlias(String s) { }

    @Override
    public boolean isDiscovering() {
        return discovering;
    }

    @Override
    public void enableDiscoveringNotifications(Notification<Boolean> notification) {

    }

    @Override
    public void disableDiscoveringNotifications() {

    }

    @Override
    public boolean startDiscovery() {
        return this.discovering = bgHandler.bgStartScanning(true);
    }

    @Override
    public boolean stopDiscovery() {
        this.discovering = false;
        return bgHandler.bgStopProcedure();
    }

    /*
       Note: It is unknown if it is possible to control "powered" state of BG dongles,
       can "USB Enable" be used (see BG spec)?
     */

    @Override
    public void setPowered(boolean b) { }

    @Override
    public boolean isPowered() {
        return true;
    }

    @Override
    public void enablePoweredNotifications(Notification<Boolean> notification) { }

    @Override
    public void disablePoweredNotifications() { }

    @Override
    public List<Device> getDevices() {
        return new ArrayList<>(devices.values());
    }

    @Override
    public URL getURL() {
        return bgHandler.getAdapterAddress();
    }

    @Override
    public void dispose() {
        synchronized (devices) {
            devices.values().stream().forEach(BluegigaUtils::dispose);
            devices.clear();
        }
        bgHandler.dispose();
    }

    @Override
    public void bluegigaEventReceived(BlueGigaResponse event) {
        if (event instanceof BlueGigaScanResponseEvent) {
            BlueGigaScanResponseEvent scanEvent = (BlueGigaScanResponseEvent) event;
            if (!isAlive()) {
                return;
            }
            synchronized (devices) {
                URL deviceURL = getURL().copyWithDevice(scanEvent.getSender());
                BluegigaDevice bluegigaDevice;
                if (!devices.containsKey(deviceURL)) {
                    bluegigaDevice = new BluegigaDevice(bgHandler, deviceURL);
                    devices.put(deviceURL, bluegigaDevice);
                    // let the device to set its name and RSSI
                    logger.debug("Discovered: {} ({}) {} ", bluegigaDevice.getURL().getDeviceAddress(),
                            bluegigaDevice.getName(), bluegigaDevice.getRSSI());
                } else {
                    bluegigaDevice = devices.get(deviceURL);
                }
                bluegigaDevice.handleScanEvent(scanEvent);
            }
        }
    }

    BluegigaDevice getDevice(URL url) {
        URL deviceURL = url.getDeviceURL();
        synchronized (devices) {
            BluegigaDevice bluegigaDevice;
            if (devices.containsKey(deviceURL)) {
                return devices.get(deviceURL);
            } else {
                bluegigaDevice = new BluegigaDevice(bgHandler, deviceURL);
                devices.put(deviceURL, bluegigaDevice);
                return bluegigaDevice;
            }
        }
    }

}
