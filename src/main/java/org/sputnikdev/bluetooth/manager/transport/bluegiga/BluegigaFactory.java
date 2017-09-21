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

import gnu.io.CommPortIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.DiscoveredAdapter;
import org.sputnikdev.bluetooth.manager.DiscoveredDevice;
import org.sputnikdev.bluetooth.manager.transport.Adapter;
import org.sputnikdev.bluetooth.manager.transport.BluetoothObjectFactory;
import org.sputnikdev.bluetooth.manager.transport.Characteristic;
import org.sputnikdev.bluetooth.manager.transport.Device;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 *
 * @author Vlad Kolotov
 */
public class BluegigaFactory implements BluetoothObjectFactory {

    public static final String BLUEGIGA_PROTOCOL_NAME = "bluegiga";
    private static final String LINUX_PORT_NAMES_REGEX =
            "((/dev/ttyS|/dev/ttyUSB|/dev/ttyACM|/dev/ttyAMA|/dev/rfcomm)[0-9]{1,3})";
    private static final String OSX_PORT_NAMES_REGEX = "(tty.(serial|usbserial|usbmodem).*)";
    private static final String WINDOWS_PORT_NAMES_REGEX = "((COM)[0-9]{1,3})";
    private static final String PORT_NAMES_REGEX =
            LINUX_PORT_NAMES_REGEX + "|" + OSX_PORT_NAMES_REGEX + "|" + WINDOWS_PORT_NAMES_REGEX;

    private Logger logger = LoggerFactory.getLogger(BluegigaFactory.class);

    private final Map<URL, BluegigaAdapter> adapters = new HashMap<>();


    /**
     * Constructs Bluegiga factory based on a list of serial port names.
     * Examples of port names:
     * OSX: /dev/tty.usbmodem1
     * Linux: /dev/ttyACM1
     * Windows: COM1
     * @param portNames list of serial port names
     */
    public BluegigaFactory(List<String> portNames) {
        adapters.putAll(discover(portNames).stream().collect(
                Collectors.toMap(BluegigaAdapter::getURL, Function.identity())));
    }

    @Override
    public BluegigaAdapter getAdapter(URL url) {
        synchronized (adapters) {
            if (adapters.containsKey(url.getAdapterURL())) {
                BluegigaAdapter bluegigaAdapter = adapters.get(url.getAdapterURL());
                if (bluegigaAdapter.isAlive()) {
                    return bluegigaAdapter;
                } else {
                    bluegigaAdapter.dispose();
                    return getAdapter(bluegigaAdapter.getPortName());
                }
            }
        }
        return null;
    }

    @Override
    public BluegigaDevice getDevice(URL url) {
        return Optional.ofNullable(getAdapter(url)).map(adapter -> adapter.getDevice(url)).orElse(null);
    }

    @Override
    public Characteristic getCharacteristic(URL url) {
        return Optional.ofNullable(getDevice(url))
                .map(device -> device.getService(url))
                .map(service -> service.getCharacteristic(url)).orElse(null);
    }

    @Override
    public List<DiscoveredAdapter> getDiscoveredAdapters() {
        synchronized (adapters) {
            return adapters.values().stream().map(BluegigaFactory::convert).collect(Collectors.toList());
        }
    }

    @Override
    public List<DiscoveredDevice> getDiscoveredDevices() {
        synchronized (adapters) {
            return adapters.values().stream().flatMap(adapter -> adapter.getDevices().stream())
                    .map(BluegigaFactory::convert).collect(Collectors.toList());
        }
    }

    @Override
    public String getProtocolName() {
        return BLUEGIGA_PROTOCOL_NAME;
    }

    private List<BluegigaAdapter> discover(List<String> portNames) {
        return Collections.list((Enumeration<CommPortIdentifier>) CommPortIdentifier.getPortIdentifiers())
                .stream()
                .filter(p -> !p.isCurrentlyOwned())
                .map(CommPortIdentifier::getName)
                .filter(portNames::contains)
                .map(this::getAdapter).filter(Objects::nonNull).collect(Collectors.toList());
    }

    private static DiscoveredAdapter convert(Adapter bluegigaAdapter) {
        return new DiscoveredAdapter(bluegigaAdapter.getURL(),
                bluegigaAdapter.getName(), bluegigaAdapter.getAlias());
    }

    private static DiscoveredDevice convert(Device bluegigaDevice) {
        return new DiscoveredDevice(bluegigaDevice.getURL(),
                bluegigaDevice.getName(), bluegigaDevice.getAlias(), bluegigaDevice.getRSSI(),
                bluegigaDevice.getBluetoothClass(),
                bluegigaDevice.isBleEnabled());
    }

    private BluegigaAdapter getAdapter(String portName) {
        BluegigaAdapter bluegigaAdapter = new BluegigaAdapter(portName);
        if (bluegigaAdapter.isAlive()) {
            return bluegigaAdapter;
        } else {
            // this is not a BG compatible device
            logger.info("Serial port {} does not represent a Bluegiga compatible device", portName);
            bluegigaAdapter.dispose();
        }
        return bluegigaAdapter;
    }

}
