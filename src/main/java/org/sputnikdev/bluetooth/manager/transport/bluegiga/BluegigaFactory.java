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
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A Bluetooth Manager Transport abstraction layer implementation based on BlugGiga library.
 * @author Vlad Kolotov
 */
public class BluegigaFactory implements BluetoothObjectFactory, BlueGigaHandlerListener {

    public static final String BLUEGIGA_PROTOCOL_NAME = "bluegiga";
    public static final String LINUX_PORT_NAMES_REGEX =
            "((/dev/ttyS|/dev/ttyUSB|/dev/ttyACM|/dev/ttyAMA|/dev/rfcomm)[0-9]{1,3})";
    public static final String OSX_PORT_NAMES_REGEX = "(/dev/tty.(serial|usbserial|usbmodem).*)";
    public static final String WINDOWS_PORT_NAMES_REGEX = "((COM)[0-9]{1,3})";
    public static final String PORT_NAMES_REGEX =
            LINUX_PORT_NAMES_REGEX + "|" + OSX_PORT_NAMES_REGEX + "|" + WINDOWS_PORT_NAMES_REGEX;

    private Logger logger = LoggerFactory.getLogger(BluegigaFactory.class);

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
            discoverAdapters();
            return adapters.values().stream().map(BluegigaFactory::convert).collect(Collectors.toList());
        }
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

    private void discoverAdapters() {
        synchronized (adapters) {
            Set<String> discoveredPorts = NRSerialPort.getAvailableSerialPorts().stream()
                .filter(p -> regexPortPattern.matcher(p).find())
                .collect(Collectors.toSet());

            Set<String> usedPorts = adapters.values().stream().map(BluegigaAdapter::getPortName)
                .collect(Collectors.toSet());

            /*
            Unfortunately RXTX driver works in different way in Linux, i.e. when a port gets opened, then it disappears
            from the list of available ports (NRSerialPort.getAvailableSerialPorts()), so we cannot use that
            to detect/remove stale adapters.
            */
            // dispose lost adapters
            /*
            Set<String> lostPorts = usedPorts.stream().filter(p -> !discoveredPorts.contains(p))
                .collect(Collectors.toSet());
            adapters.entrySet().removeIf(entry -> {
                if (lostPorts.contains(entry.getValue().getPortName())) {
                    entry.getValue().dispose();
                    return true;
                }
                return false;
            });
            */

            // new ports
            Map<URL, BluegigaAdapter> newAdapters = discoveredPorts.stream().filter(p -> !usedPorts.contains(p))
                .map(this::tryToCreateAdapter).filter(Objects::nonNull)
                .collect(Collectors.toMap(BluegigaAdapter::getURL, Function.identity()));
            // clean up stale objects
            newAdapters.forEach((key, adapter) -> {
                if (adapters.containsKey(key)) {
                    removeAdapter(adapters.get(key));
                }
            });
            adapters.putAll(newAdapters);

            // check if adapters still alive
            adapters.forEach((url, bluegigaAdapter) -> {
                if (!bluegigaAdapter.isAlive()) {
                    removeAdapter(bluegigaAdapter);
                }
            });
        }
    }

    private void removeAdapter(BluegigaAdapter bluegigaAdapter) {
        try {
            bluegigaAdapter.dispose();
        } catch (Exception ex) {
            logger.warn("Could not dispose adapter: " + bluegigaAdapter.getPortName(), ex);
        }
        adapters.remove(bluegigaAdapter.getURL());
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
        bluegigaHandler.addHandlerListener(exception -> {
            synchronized (adapters) {
                removeAdapter(bluegigaAdapter);
            }
        });
        return bluegigaAdapter;
    }

}
