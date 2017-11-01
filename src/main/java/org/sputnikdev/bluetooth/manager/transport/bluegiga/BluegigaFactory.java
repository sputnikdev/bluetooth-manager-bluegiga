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

import com.zsmartsystems.bluetooth.bluegiga.BlueGigaHandlerListener;
import gnu.io.NRSerialPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.DiscoveredAdapter;
import org.sputnikdev.bluetooth.manager.DiscoveredDevice;
import org.sputnikdev.bluetooth.manager.transport.Adapter;
import org.sputnikdev.bluetooth.manager.transport.BluetoothObjectFactory;
import org.sputnikdev.bluetooth.manager.transport.Characteristic;
import org.sputnikdev.bluetooth.manager.transport.Device;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 *
 * @author Vlad Kolotov
 */
public class BluegigaFactory implements BluetoothObjectFactory, BlueGigaHandlerListener {

    public static final String BLUEGIGA_PROTOCOL_NAME = "bluegiga";
    public static final String LINUX_PORT_NAMES_REGEX =
            "((/dev/ttyS|/dev/ttyUSB|/dev/ttyACM|/dev/ttyAMA|/dev/rfcomm)[0-9]{1,3})";
    public static final String OSX_PORT_NAMES_REGEX = "(tty.(serial|usbserial|usbmodem).*)";
    public static final String WINDOWS_PORT_NAMES_REGEX = "((COM)[0-9]{1,3})";
    public static final String PORT_NAMES_REGEX =
            LINUX_PORT_NAMES_REGEX + "|" + OSX_PORT_NAMES_REGEX + "|" + WINDOWS_PORT_NAMES_REGEX;

    private Logger logger = LoggerFactory.getLogger(BluegigaFactory.class);

    //private final Set<String> ports = new CopyOnWriteArraySet<>();
    private final Pattern regexPortPattern;
    private final Map<URL, BluegigaAdapter> adapters = new ConcurrentHashMap<>();

    /**
     * Constructs Bluegiga factory based on a broad regex pattern to match serial ports:
     * BluegigaFactory.PORT_NAMES_REGEX
     */
    public BluegigaFactory() {
        regexPortPattern = Pattern.compile(PORT_NAMES_REGEX);
    }

    /**
     * Constructs Bluegiga factory based on a regular expression to match serial port names.
     * Examples of port names:
     * OSX: /dev/tty.usbmodem1
     * Linux: /dev/ttyACM1
     * Windows: COM1
     * @param portNamesRegexPattern regular expression for serial port matching
     */
    public BluegigaFactory(String portNamesRegexPattern) {
        regexPortPattern = Pattern.compile(portNamesRegexPattern);
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
                    if (checkIfPortExists(bluegigaAdapter.getPortName())) {
                        return tryToCreateAdapter(bluegigaAdapter.getPortName());
                    }
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
            if (autodiscovery) {
                discoverPorts();
            }
            discoverAdapters();
            checkAdaptersAlive();
            return adapters.values().stream().map(BluegigaFactory::convert).collect(Collectors.toList());
        }
    }

    private void checkAdaptersAlive() {
        adapters.forEach((url, bluegigaAdapter) -> {
            if (!bluegigaAdapter.isAlive()) {
                bluegigaAdapter.dispose();
                adapters.remove(url);
                ports.remove(bluegigaAdapter.getPortName());
            }
        });
    }

    private boolean checkIfPortExists(String portName) {
        try {
            return NRSerialPort.getAvailableSerialPorts().contains(portName);
        } catch (Exception ex) {
            logger.warn("Could not autodiscover BlueGiga serial ports.", ex);
            return false;
        }
    }

    @Override
    public List<DiscoveredDevice> getDiscoveredDevices() {
        synchronized (adapters) {
            return adapters.values().stream().filter(BluegigaAdapter::isAlive)
                    .flatMap(adapter -> adapter.getDevices().stream())
                    .map(BluegigaFactory::convert).collect(Collectors.toList());
        }
    }

    @Override
    public String getProtocolName() {
        return BLUEGIGA_PROTOCOL_NAME;
    }

    @Override
    public void bluegigaClosed(Exception reason) {
        logger.warn("Blueguga handler was closed due to the reason:", reason);
    }

    private void discoverPorts() {
        try {
            synchronized (ports) {
                ports.addAll(NRSerialPort.getAvailableSerialPorts().stream()
                    .filter(port -> !ports.contains(port)).collect(Collectors.toSet()));
            }
        } catch (Exception ex) {
            logger.warn("Could not autodiscover BlueGiga serial ports.", ex);
        }
    }

    private void discoverAdapters() {
        synchronized (adapters) {
            Set<String> usedPorts = adapters.values().stream().map(BluegigaAdapter::getPortName)
                .collect(Collectors.toSet());
            Set<String> newPorts = ports.stream().filter(p -> !usedPorts.contains(p)).collect(Collectors.toSet());

            Map<URL, BluegigaAdapter> newAdapters = newPorts.stream().map(this::tryToCreateAdapter).filter(Objects::nonNull)
                    .collect(Collectors.toMap(BluegigaAdapter::getURL, Function.identity()));

            newAdapters.forEach((key, adapter) -> {
                if (adapters.containsKey(key)) {
                    BluegigaAdapter adapterToRemove = adapters.get(key);
                    adapters.remove(key);
                    ports.remove(adapterToRemove.getPortName());
                    adapterToRemove.dispose();
                }
            });

            adapters.putAll(newAdapters);
        }
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

    private BluegigaAdapter tryToCreateAdapter(String portName) {
        try {
            return createAdapter(portName);
        } catch (Exception ex) {
            logger.warn("Could not create adapter for port: " + portName, ex);
        }
        return null;
    }

    private BluegigaAdapter createAdapter(String portName) {
        BluegigaHandler bluegigaHandler = BluegigaHandler.create(portName);
        BluegigaAdapter bluegigaAdapter = new BluegigaAdapter(bluegigaHandler);
        URL adapterURL = bluegigaAdapter.getURL();
        bluegigaHandler.addHandlerListener(exception -> {
            synchronized (adapters) {
                bluegigaAdapter.dispose();
                adapters.remove(adapterURL);
                ports.remove(portName);
            }
        });
        return bluegigaAdapter;
    }

}
