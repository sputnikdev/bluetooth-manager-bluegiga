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
import com.zsmartsystems.bluetooth.bluegiga.BlueGigaException;
import com.zsmartsystems.bluetooth.bluegiga.BlueGigaResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaScanResponseEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.system.BlueGigaGetInfoResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.transport.Adapter;
import org.sputnikdev.bluetooth.manager.transport.Device;
import org.sputnikdev.bluetooth.manager.transport.Notification;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bluegiga transport adapter.
 * @author Vlad Kolotov
 * @author Chris Jackson
 */
class BluegigaAdapter implements Adapter, BlueGigaEventListener {

    public static final String BLUEGIGA_NAME = "Bluegiga";

    private final Logger logger = LoggerFactory.getLogger(BluegigaAdapter.class);

    private BlueGigaGetInfoResponse info;
    private boolean discovering;
    private final BluegigaHandler bgHandler;
    private final Map<URL, BluegigaDevice> devices = new HashMap<>();
    private Notification<Boolean> discoveringNotification;
    // just a local cache, BlueGiga adapters do not support aliases
    private String alias;

    private BluegigaAdapter(BluegigaHandler bluegigaHandler) {
        bgHandler = bluegigaHandler;
    }

    public boolean isAlive() {
        return getURL() != null && bgHandler.isAlive();
    }

    @Override
    public String getName() {
        return BLUEGIGA_NAME + Optional.ofNullable(info)
            .map(inf -> " v" + inf.getMajor() + "." + inf.getMinor()).orElse("");
    }

    @Override
    public boolean isDiscovering() {
        if (!bgHandler.isAlive()) {
            throw new BlueGigaException("BlueGiga handler is dead.");
        }
        return discovering;
    }

    @Override
    public void enableDiscoveringNotifications(Notification<Boolean> notification) {
        discoveringNotification = notification;
    }

    @Override
    public void disableDiscoveringNotifications() {
        discoveringNotification = null;
    }

    @Override
    public boolean startDiscovery() {
        boolean discoveryStatus = bgHandler.bgStartScanning();
        if (!discovering && discoveryStatus) {
            discovering = true;
            notifyDiscovering(true);
        }
        return discoveryStatus;
    }

    @Override
    public boolean stopDiscovery() {
        if (discovering) {
            discovering = false;
            notifyDiscovering(false);
            if (bgHandler.isAlive()) {
                bgHandler.bgStopProcedure();
            }
        }
        return true;
    }

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
        bgHandler.removeEventListener(this);
        bgHandler.runInSynchronizedContext(() -> {

            try {
                stopDiscovery();
            } catch (Exception ex) {
                logger.warn("Could not stop discovery process", ex);
            }

            devices.values().forEach(device -> {
                try {
                    device.dispose();
                } catch (Exception ex) {
                    logger.warn("Could not dispose Bluegiga device", ex);
                }
            });
            devices.clear();

            bgHandler.dispose();
        });
    }

    @Override
    public void bluegigaEventReceived(BlueGigaResponse event) {
        if (event instanceof BlueGigaScanResponseEvent) {
            BlueGigaScanResponseEvent scanEvent = (BlueGigaScanResponseEvent) event;
            synchronized (devices) {
                URL deviceURL = getURL().copyWithDevice(scanEvent.getSender());
                if (!devices.containsKey(deviceURL)) {
                    BluegigaDevice bluegigaDevice = createDevice(deviceURL);
                    devices.put(deviceURL, bluegigaDevice);
                    // let the device to set its name and RSSI
                    bluegigaDevice.bluegigaEventReceived(scanEvent);
                    logger.debug("Discovered: {} ({}) {} ", bluegigaDevice.getURL().getDeviceAddress(),
                            bluegigaDevice.getName(), bluegigaDevice.getRSSI());
                }
            }
        }
    }

    /*
    The following methods are not supporeted by Bluegiga
     */

    @Override
    public void setPowered(boolean powered) { /* do nothing */ }

    @Override
    public boolean isPowered() {
        return true;
    }

    @Override
    public void enablePoweredNotifications(Notification<Boolean> notification) { /* do nothing */ }

    @Override
    public void disablePoweredNotifications() { /* do nothing */ }

    @Override
    public String getAlias() {
        return alias;
    }

    @Override
    public void setAlias(String alias) {
        this.alias = alias;
    }

    protected BluegigaDevice getDevice(URL url) {
        URL deviceURL = url.getDeviceURL();
        synchronized (devices) {
            if (devices.containsKey(deviceURL)) {
                return devices.get(deviceURL);
            } else {
                BluegigaDevice bluegigaDevice = createDevice(deviceURL);
                devices.put(deviceURL, bluegigaDevice);
                return bluegigaDevice;
            }
        }
    }

    protected BluegigaDevice createDevice(URL address) {
        return new BluegigaDevice(bgHandler, address);
    }

    protected static BluegigaAdapter create(BluegigaHandler bluegigaHandler) {
        BluegigaAdapter bluegigaAdapter = new BluegigaAdapter(bluegigaHandler);
        bluegigaAdapter.init();
        return bluegigaAdapter;
    }

    protected String getPortName() {
        return bgHandler.getPortName();
    }

    private void init() {
        info = bgHandler.bgGetInfo();
        bgHandler.addEventListener(this);
    }

    private void notifyDiscovering(boolean isDiscovering) {
        Notification<Boolean> notification = discoveringNotification;
        if (notification != null) {
            try {
                notification.notify(isDiscovering);
            } catch (Exception ex) {
                logger.error("Could not notify discovering notification", ex);
            }
        }
    }

}
