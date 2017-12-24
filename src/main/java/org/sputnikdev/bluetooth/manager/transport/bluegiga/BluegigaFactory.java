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

import java.util.List;
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
 * A Bluetooth Manager Transport abstraction layer implementation based on BlugGiga library.
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
        URL adapterURL = url.getAdapterURL();
        synchronized (adapters) {
            if (adapters.containsKey(adapterURL)) {
                BluegigaAdapter bluegigaAdapter = adapters.get(adapterURL);
                if (bluegigaAdapter.isAlive()) {
                    return bluegigaAdapter;
                } else {
                    adapters.remove(adapterURL);
                    bluegigaAdapter.dispose();
                    if (checkIfPortExists(bluegigaAdapter.getPortName())) {
                        bluegigaAdapter = tryToCreateAdapter(bluegigaAdapter.getPortName());
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
        return Optional.ofNullable(getAdapter(url.getAdapterURL()))
            .map(adapter -> adapter.getDevice(url.getDeviceURL())).orElse(null);
    }

    @Override
    public Characteristic getCharacteristic(URL url) {
        return Optional.ofNullable(getDevice(url.getDeviceURL()))
                .map(device -> device.getService(url.getServiceURL()))
                .map(service -> service.getCharacteristic(url.getCharacteristicURL())).orElse(null);
    }

    @Override
    public List<DiscoveredAdapter> getDiscoveredAdapters() {
        discoverAdapters();
        return adapters.values().stream().map(BluegigaFactory::convert).collect(Collectors.toList());
    }

    @Override
    public List<DiscoveredDevice> getDiscoveredDevices() {
        return adapters.values().stream().filter(BluegigaAdapter::isAlive)
                .flatMap(adapter -> adapter.getDevices().stream())
                .map(BluegigaFactory::convert).collect(Collectors.toList());
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
     *  for BlueGiga dapters</li>
     * </ul>
     * @param config configuration
     */
    @Override
    public void configure(Map<String, Object> config) {
        String serialPortConfig = (String) config.get(CONFIG_SERIAL_PORT_REGEX);
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
        adapters.values().forEach(adapter -> {
            try {
                adapter.dispose();
            } catch (Exception ignore) {
                logger.warn("Could not dispose adapter: {}", adapter.getPortName());
            }
        });
        adapters.clear();
    }

    protected BluegigaAdapter createAdapter(String portName) {
        BluegigaHandler bluegigaHandler = BluegigaHandler.create(portName);
        try {
            BluegigaAdapter bluegigaAdapter = BluegigaAdapter.create(bluegigaHandler);
            bluegigaHandler.addHandlerListener(exception -> {
                synchronized (adapters) {
                    removeAdapter(bluegigaAdapter);
                }
            });
            return bluegigaAdapter;
        } catch (Exception ex) {
            bluegigaHandler.dispose();
            logger.warn("Could not create adapter for port: " + portName, ex);
            return null;
        }
    }

    protected boolean matchPort(String port) {
        return regexPortPattern.matcher(port).find();
    }

    private void discoverAdapters() {
        synchronized (adapters) {
            Set<String> discoveredPorts = NRSerialPort.getAvailableSerialPorts().stream()
                .filter(this::matchPort)
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
            logger.warn("Could not create adapter handler for port: " + portName, ex);
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
