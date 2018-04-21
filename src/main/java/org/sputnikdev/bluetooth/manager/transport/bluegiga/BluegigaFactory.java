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

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * A Bluetooth Manager Transport abstraction layer implementation based on BlueGiga library.
 * @author Vlad Kolotov
 */
public class BluegigaFactory implements BluetoothObjectFactory {

    public static final String CONFIG_SERIAL_PORT_REGEX = "serialPortRegex";
    public static final String BLUEGIGA_PROTOCOL_NAME = "bluegiga";
    public static final String LINUX_SERIAL_PORT_NAMES_REGEX = "((/dev/ttyACM)[0-9]{1,3})";
    public static final String OSX_SERIAL_PORT_NAMES_REGEX = "(/dev/tty.(usbmodem).*)";
    public static final String WINDOWS_SERIAL_PORT_NAMES_REGEX = "((COM)[0-9]{1,3})";
    public static final String PORT_NAMES_REGEX =
            LINUX_SERIAL_PORT_NAMES_REGEX + "|" + OSX_SERIAL_PORT_NAMES_REGEX + "|" + WINDOWS_SERIAL_PORT_NAMES_REGEX;
    private static final String CONFIG_SERIAL_PORT_DEFAULT = "(?!)";

    private Logger logger = LoggerFactory.getLogger(BluegigaFactory.class);

    private Pattern regexPortPattern = Pattern.compile(CONFIG_SERIAL_PORT_DEFAULT);
    private final Map<URL, BluegigaAdapter> adapters = new ConcurrentHashMap<>();

    /**
     * Constructs Bluegiga factory with the default regular expression to match nothing.
     */
    public BluegigaFactory() { }

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
        //TODO implement a background process that discovers adapters (instead of doing it in getDiscoveredAdapters)
        logger.debug("Adapter requested: {}", url);
        URL adapterURL = url.getAdapterURL();
        synchronized (adapters) {
            if (adapters.containsKey(adapterURL)) {
                BluegigaAdapter bluegigaAdapter = adapters.get(adapterURL);
                logger.debug("Checking if existing adapter is alive: {}", url);
                if (bluegigaAdapter.isAlive()) {
                    logger.debug("Adapter is alive: {}", url);
                    return bluegigaAdapter;
                } else {
                    logger.debug("Adapter is dead, trying to reinstate adapter: {}", url);
                    adapters.remove(adapterURL);
                    bluegigaAdapter.dispose();
                    logger.debug("Checking if the corresponding port still exists: {} / {}", url,
                            bluegigaAdapter.getPortName());
                    if (checkIfPortExists(bluegigaAdapter.getPortName())) {
                        logger.debug("Trying to crete adapter for port: {} / {}", url,
                                bluegigaAdapter.getPortName());
                        bluegigaAdapter = tryToCreateAdapter(bluegigaAdapter.getPortName());
                        logger.debug("Adapter created (maybe not): {}", url);
                        if (bluegigaAdapter != null) {
                            adapters.put(adapterURL, bluegigaAdapter);
                        }
                        return bluegigaAdapter;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public BluegigaDevice getDevice(URL url) {
        logger.debug("Device requested: {}", url);
        BluegigaDevice device = Optional.ofNullable(getAdapter(url.getAdapterURL()))
            .map(adapter -> adapter.getDevice(url.getDeviceURL())).orElse(null);
        logger.debug("Device returned: {} / {}", url, device != null ? Integer.toHexString(device.hashCode()) : null);
        return device;
    }

    @Override
    public Characteristic getCharacteristic(URL url) {
        logger.debug("Characteristic requested: {}", url);
        BluegigaCharacteristic characteristic = Optional.ofNullable(getDevice(url.getDeviceURL()))
                .map(device -> device.getService(url.getServiceURL()))
                .map(service -> service.getCharacteristic(url.getCharacteristicURL())).orElse(null);
        logger.debug("Characteristic returned: {} / {}", url, characteristic);
        return characteristic;
    }

    @Override
    public Set<DiscoveredAdapter> getDiscoveredAdapters() {
        logger.debug("Discovered adapters requested");
        discoverAdapters();
        Set<DiscoveredAdapter> discovered = adapters.values().stream().map(BluegigaFactory::convert)
                .collect(Collectors.toSet());
        logger.debug("Discovered adapters: [{}]", discovered.stream().map(DiscoveredAdapter::getURL)
                .map(Object::toString).collect(Collectors.joining(", ")));
        return discovered;
    }

    @Override
    public Set<DiscoveredDevice> getDiscoveredDevices() {
        logger.debug("Discovered devices requested");
        Set<DiscoveredDevice> discovered = adapters.values().stream().filter(BluegigaAdapter::isAlive)
                .flatMap(adapter -> adapter.getDevices().stream())
                .filter(device -> ((BluegigaDevice) device).getLastDiscovered() != null)
                .map(BluegigaFactory::convert).collect(Collectors.toSet());
        logger.debug("Discovered devices: [{}]", discovered.stream().map(DiscoveredDevice::getURL).map(Object::toString)
                .collect(Collectors.joining(", ")));
        return discovered;
    }

    @Override
    public String getProtocolName() {
        return BLUEGIGA_PROTOCOL_NAME;
    }

    /**
     * Configures the factory.
     * The following properties are supported:
     * <ul>
     *  <li>serialPortRegex - a regular expression to be used for autodiscovery of serial ports
     *  for BlueGiga adapters</li>
     * </ul>
     * @param config configuration
     */
    @Override
    public void configure(Map<String, Object> config) {
        logger.debug("Configuring factory: {}", config);
        String serialPortConfig = (String) config.get(CONFIG_SERIAL_PORT_REGEX);
        logger.debug("Regex serial port pattern: {}", serialPortConfig);
        if (serialPortConfig == null || serialPortConfig.trim().isEmpty()) {
            regexPortPattern = Pattern.compile(CONFIG_SERIAL_PORT_DEFAULT);
            return;
        }

        try {
            regexPortPattern = Pattern.compile(serialPortConfig);
        } catch (PatternSyntaxException ex) {
            throw new BluegigaException("Serial port regex is not valid", ex);
        }
    }

    /**
     * Disposes the factory.
     */
    public void dispose() {
        logger.warn("Disposing factory: {}", Integer.toHexString(hashCode()));
        adapters.values().forEach(adapter -> {
            try {
                adapter.dispose();
            } catch (Exception ignore) {
                logger.warn("Could not dispose adapter: {}", adapter.getPortName());
            }
        });
        adapters.clear();
        logger.debug("Factory disposed: {}", Integer.toHexString(hashCode()));
    }

    @Override
    public void dispose(URL url) {
        logger.debug("Bluetooth object disposal requested: {}", url);
        URL bluegigaURL = url.copyWithProtocol(BLUEGIGA_PROTOCOL_NAME);
        if (bluegigaURL.isAdapter()) {
            adapters.computeIfPresent(bluegigaURL, (key, adapter) -> {
                adapter.dispose();
                return null;
            });
        } else if (bluegigaURL.isDevice()) {
            // characteristics cannot be disposed separately
            adapters.computeIfPresent(bluegigaURL.getAdapterURL(), (key, adapter) -> {
                adapter.disposeDevice(bluegigaURL.getDeviceURL());
                return adapter;
            });
        } else if (bluegigaURL.isCharacteristic()) {
            // do nothing
        }
    }

    protected BluegigaAdapter createAdapter(String portName) {
        logger.debug("Creating new bluegiga handler for port: {}", portName);
        BluegigaHandler bluegigaHandler = BluegigaHandler.create(portName);
        try {
            logger.debug("Creating a new adapter for port: {} / {}", portName,
                    bluegigaHandler.getAdapterAddress());
            BluegigaAdapter bluegigaAdapter = BluegigaAdapter.create(bluegigaHandler);
            bluegigaHandler.addHandlerListener(exception -> {
                logger.debug("An exception occurred in blugiga handler: {}", exception.getMessage());
                synchronized (adapters) {
                    removeAdapter(bluegigaAdapter);
                }
            });
            logger.debug("Adapter created: {} / {}", bluegigaAdapter.getURL(),
                    Integer.toHexString(bluegigaAdapter.hashCode()));
            return bluegigaAdapter;
        } catch (Exception ex) {
            logger.warn("Could not create a new adapter for port: " + portName, ex);
            bluegigaHandler.dispose();
            return null;
        }
    }

    protected boolean matchPort(String port) {
        return regexPortPattern.matcher(port).find();
    }

    private void discoverAdapters() {
        logger.debug("Discovering adapters");
        synchronized (adapters) {
            Set<String> discoveredPorts = NRSerialPort.getAvailableSerialPorts().stream()
                .filter(this::matchPort)
                .collect(Collectors.toSet());
            logger.debug("Discovered ports: [{}]", discoveredPorts.stream().collect(Collectors.joining(", ")));

            Set<String> usedPorts = adapters.values().stream().map(BluegigaAdapter::getPortName)
                .collect(Collectors.toSet());
            logger.debug("Ports already in use: [{}]", usedPorts.stream().collect(Collectors.joining(", ")));

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
            logger.debug("New adapters: [{}]", newAdapters.keySet().stream().map(Object::toString)
                    .collect(Collectors.joining(", ")));
            // clean up stale objects
            newAdapters.forEach((key, adapter) -> {
                if (adapters.containsKey(key)) {
                    logger.debug("Removing a stale adapter: {}", key);
                    removeAdapter(adapters.get(key));
                }
            });
            adapters.putAll(newAdapters);

            // check if adapters still alive
            adapters.forEach((url, bluegigaAdapter) -> {
                if (!bluegigaAdapter.isAlive()) {
                    logger.debug("Removing a dead adapter: {}", url);
                    removeAdapter(bluegigaAdapter);
                }
            });
        }
    }

    private void removeAdapter(BluegigaAdapter bluegigaAdapter) {
        try {
            bluegigaAdapter.dispose();
        } catch (Exception ex) {
            logger.debug("Error occurred while disposing adapter: " + bluegigaAdapter.getPortName(), ex);
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
            logger.warn("Error occurred while creating a new adapter for port: {}, {}", portName, ex.getMessage());
        }
        return null;
    }

    private boolean checkIfPortExists(String portName) {
        try {
            return NRSerialPort.getAvailableSerialPorts().contains(portName);
        } catch (Exception ex) {
            logger.warn("Could not verify if port exists.", ex);
            return false;
        }
    }

}
