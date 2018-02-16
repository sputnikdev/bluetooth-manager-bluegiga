package org.sputnikdev.bluetooth.manager.transport.bluegiga;

import com.zsmartsystems.bluetooth.bluegiga.BlueGigaCommand;
import com.zsmartsystems.bluetooth.bluegiga.BlueGigaEventListener;
import com.zsmartsystems.bluetooth.bluegiga.BlueGigaException;
import com.zsmartsystems.bluetooth.bluegiga.BlueGigaHandlerListener;
import com.zsmartsystems.bluetooth.bluegiga.BlueGigaResponse;
import com.zsmartsystems.bluetooth.bluegiga.BlueGigaSerialHandler;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaAttributeValueEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaAttributeWriteCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaAttributeWriteResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaFindInformationCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaFindInformationFoundEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaFindInformationResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaGroupFoundEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaProcedureCompletedEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaReadByGroupTypeCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaReadByGroupTypeResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaReadByHandleCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaReadByHandleResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaReadByTypeCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaReadByTypeResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.BlueGigaConnectionStatusEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.BlueGigaDisconnectCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.BlueGigaDisconnectResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.BlueGigaDisconnectedEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.BlueGigaGetRssiCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.BlueGigaGetRssiResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaConnectDirectCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaConnectDirectResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaDiscoverCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaDiscoverResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaEndProcedureCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaEndProcedureResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaSetScanParametersCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.system.BlueGigaGetConnectionsCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.system.BlueGigaGetConnectionsResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.system.BlueGigaGetInfoCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.system.BlueGigaGetInfoResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.system.BlueGigaHelloCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.system.BlueGigaHelloResponse;
import com.zsmartsystems.bluetooth.bluegiga.enumeration.AttributeValueType;
import com.zsmartsystems.bluetooth.bluegiga.enumeration.BgApiResponse;
import com.zsmartsystems.bluetooth.bluegiga.enumeration.BluetoothAddressType;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;
import org.sputnikdev.bluetooth.URL;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.powermock.api.mockito.PowerMockito.verifyPrivate;
import static org.powermock.api.mockito.PowerMockito.when;

@RunWith(PowerMockRunner.class)
public class BluegigaHandlerTest {

    private static final String PORT_NAME = "/dev/tty.usbmodem1";
    private static final int CONNECTION_HANDLE = 1;
    private static final int CHARACTERISTIC_HANDLE = 5;
    private static final URL ADAPTER_URL = new URL("12:34:56:78:90:12", null);
    private static final URL DEVICE_URL = ADAPTER_URL.copyWithDevice("11:22:33:44:55:66");

    @Mock
    private BlueGigaSerialHandler bgHandler;
    @Mock
    private URL adapterAddress = ADAPTER_URL;

    @InjectMocks
    @Spy
    private BluegigaHandler handler = new BluegigaHandler(PORT_NAME);

    @Test
    public void testGetPortName() throws Exception {
        assertEquals(PORT_NAME, handler.getPortName());
    }

    @Test
    public void testAddHandlerListener() throws Exception {
        BlueGigaHandlerListener listener = mock(BlueGigaHandlerListener.class);
        handler.addHandlerListener(listener);

        verify(bgHandler).addHandlerListener(listener);
    }

    @Test
    public void testAddEventListener() throws Exception {
        BlueGigaEventListener listener = mock(BlueGigaEventListener.class);
        handler.addEventListener(listener);

        verify(bgHandler).addEventListener(listener);

        handler.removeEventListener(listener);

        verify(bgHandler).removeEventListener(listener);
    }

    @Test
    public void testIsAlive() throws Exception {
        assertFalse(handler.isAlive());

        when(bgHandler.isAlive()).thenReturn(true);
        assertFalse(handler.isAlive());

        mockTransaction(BlueGigaHelloCommand.class, null);
        assertFalse(handler.isAlive());

        BlueGigaHelloResponse helloResponse = mock(BlueGigaHelloResponse.class);
        mockTransaction(BlueGigaHelloCommand.class, helloResponse);
        assertTrue(handler.isAlive());

        when(bgHandler.sendTransaction(isA(BlueGigaHelloCommand.class), eq(BlueGigaHelloResponse.class), anyLong()))
                .thenThrow(Exception.class);
        assertFalse(handler.isAlive());

        verify(bgHandler, times(5)).isAlive();
    }

    @Test
    public void testGetAdapterAddress() throws Exception {
        assertEquals(adapterAddress, handler.getAdapterAddress());
    }

    @Test
    public void testConnect() throws Exception {
        // mocking some stuff for the connection procedure
        // performing the invokaction
        BlueGigaConnectionStatusEvent connectionEvent =
            mockConnect(BgApiResponse.SUCCESS, CONNECTION_HANDLE, DEVICE_URL);

        assertEquals(CONNECTION_HANDLE, connectionEvent.getConnection());
    }

    @Test(expected = BluegigaException.class)
    public void testConnectError() throws Exception {
        mockConnect(BgApiResponse.TIMEOUT, CONNECTION_HANDLE, DEVICE_URL);
        handler.connect(DEVICE_URL, BluetoothAddressType.UNKNOWN);
    }

    @Test
    public void testDisconnect() throws Exception {
        BlueGigaConnectionStatusEvent connectionEvent =
            mockConnect(BgApiResponse.SUCCESS, CONNECTION_HANDLE, DEVICE_URL);
        assertEquals(CONNECTION_HANDLE, connectionEvent.getConnection());

        BlueGigaDisconnectedEvent disconnectedEvent = mockDisconnect(BgApiResponse.SUCCESS, CONNECTION_HANDLE);
        assertEquals(CONNECTION_HANDLE, disconnectedEvent.getConnection());

        verifyPrivate(handler).invoke("bgDisconnect", CONNECTION_HANDLE);
    }

    @Test
    public void testGetServices() throws Exception {
        mockAsyncMultiEventProcedure(BlueGigaReadByGroupTypeCommand.class, BlueGigaReadByGroupTypeResponse.class,
            mockEvent(BlueGigaGroupFoundEvent.class, CONNECTION_HANDLE),
            mockEvent(BlueGigaGroupFoundEvent.class, CONNECTION_HANDLE),
            mockEvent(BlueGigaGroupFoundEvent.class, 2),
            mockEvent(BlueGigaProcedureCompletedEvent.class, CONNECTION_HANDLE));
        List<BlueGigaGroupFoundEvent> foundEvents = handler.getServices(CONNECTION_HANDLE);
        assertEquals(2, foundEvents.size());
    }

    @Test
    public void testGetCharacteristics() throws Exception {
        mockAsyncMultiEventProcedure(BlueGigaFindInformationCommand.class, BlueGigaFindInformationResponse.class,
            mockEvent(BlueGigaFindInformationFoundEvent.class, CONNECTION_HANDLE),
            mockEvent(BlueGigaFindInformationFoundEvent.class, CONNECTION_HANDLE),
            mockEvent(BlueGigaGroupFoundEvent.class, 2),
            mockEvent(BlueGigaProcedureCompletedEvent.class, CONNECTION_HANDLE));
        List<BlueGigaFindInformationFoundEvent> foundEvents = handler.getCharacteristics(CONNECTION_HANDLE);
        assertEquals(2, foundEvents.size());
    }

    @Test
    public void testGetDeclarations() throws Exception {
        mockAsyncMultiEventProcedure(BlueGigaReadByTypeCommand.class, BlueGigaReadByTypeResponse.class,
            mockEvent(BlueGigaAttributeValueEvent.class, CONNECTION_HANDLE,
                    AttributeValueType.ATTCLIENT_ATTRIBUTE_VALUE_TYPE_READ_BY_TYPE),
            mockEvent(BlueGigaAttributeValueEvent.class, CONNECTION_HANDLE,
                    AttributeValueType.ATTCLIENT_ATTRIBUTE_VALUE_TYPE_READ_BY_TYPE),
            mockEvent(BlueGigaGroupFoundEvent.class, 2),
            mockEvent(BlueGigaProcedureCompletedEvent.class, CONNECTION_HANDLE));
        List<BlueGigaAttributeValueEvent> events = handler.getDeclarations(CONNECTION_HANDLE);
        assertEquals(2, events.size());
    }

    @Test
    public void testReadCharacteristic() throws Exception {

        int[] data = {1, 2, 3, 4, 5};

        BlueGigaAttributeValueEvent expected = mockValueEvent(CONNECTION_HANDLE, CHARACTERISTIC_HANDLE, data);
        BlueGigaAttributeValueEvent otherEvent = mockValueEvent(2, CHARACTERISTIC_HANDLE, data);
        BlueGigaAttributeValueEvent anotherEvent = mockValueEvent(CONNECTION_HANDLE, 6, data);

        mockAsyncProcedure(BlueGigaReadByHandleCommand.class,
            BlueGigaReadByHandleResponse.class, BgApiResponse.SUCCESS, CONNECTION_HANDLE, expected);
        mockAsyncProcedure(BlueGigaReadByHandleCommand.class,
            BlueGigaReadByHandleResponse.class, BgApiResponse.SUCCESS, 2, otherEvent);
        mockAsyncProcedure(BlueGigaReadByHandleCommand.class,
            BlueGigaReadByHandleResponse.class, BgApiResponse.SUCCESS, CONNECTION_HANDLE, anotherEvent);

        BlueGigaAttributeValueEvent actual = handler.readCharacteristic(CONNECTION_HANDLE, CHARACTERISTIC_HANDLE);
        assertArrayEquals(expected.getValue(), actual.getValue());

    }

    private BlueGigaAttributeValueEvent mockValueEvent(int connectionHandle, int charecteristicHandle, int[] data) {
        BlueGigaAttributeValueEvent event = mock(BlueGigaAttributeValueEvent.class);
        when(event.getValue()).thenReturn(data);
        when(event.getConnection()).thenReturn(connectionHandle);
        when(event.getAttHandle()).thenReturn(charecteristicHandle);
        return event;
    }

    @Test
    public void testWriteCharacteristic() throws Exception {
        int[] data = {1, 2, 3, 4, 5};

        BlueGigaProcedureCompletedEvent expected = mockEvent(BlueGigaProcedureCompletedEvent.class, CONNECTION_HANDLE);
        when(expected.getChrHandle()).thenReturn(CHARACTERISTIC_HANDLE);
        when(expected.getResult()).thenReturn(BgApiResponse.SUCCESS);

        mockAsyncProcedure(BlueGigaAttributeWriteCommand.class, BlueGigaAttributeWriteResponse.class,
            BgApiResponse.SUCCESS, CONNECTION_HANDLE, expected);

        BlueGigaProcedureCompletedEvent actual = handler.writeCharacteristic(CONNECTION_HANDLE, CHARACTERISTIC_HANDLE, data);

        assertEquals(BgApiResponse.SUCCESS, actual.getResult());
    }

    @Test
    public void testWriteCharacteristicWithoutResponse() throws Exception {
        int[] data = {1, 2, 3, 4, 5};

        BlueGigaAttributeWriteResponse writeResponse = mock(BlueGigaAttributeWriteResponse.class);
        when(writeResponse.getResult()).thenReturn(BgApiResponse.SUCCESS);

        mockTransaction(BlueGigaAttributeWriteCommand.class, writeResponse);

        assertTrue(handler.writeCharacteristicWithoutResponse(CONNECTION_HANDLE, CHARACTERISTIC_HANDLE, data));

        when(bgHandler.sendTransaction(isA(BlueGigaAttributeWriteCommand.class),
                eq(BlueGigaAttributeWriteResponse.class), anyLong()))
            .thenReturn(writeResponse);
    }

    @Test
    public void testBgGetInfo() throws Exception {
        BlueGigaGetInfoResponse response = mock(BlueGigaGetInfoResponse.class);
        when(response.getMajor()).thenReturn(1);
        when(response.getMinor()).thenReturn(3);
        when(response.getBuild()).thenReturn(12345);
        when(response.getHardware()).thenReturn(5);
        when(response.getLlVersion()).thenReturn(6);
        when(response.getPatch()).thenReturn(2);
        when(response.getProtocolVersion()).thenReturn(45);

        mockTransaction(BlueGigaGetInfoCommand.class, response);

        BlueGigaGetInfoResponse actual = handler.bgGetInfo();

        assertEquals(1, actual.getMajor());
        assertEquals(3, actual.getMinor());
        assertEquals(12345, actual.getBuild());
        assertEquals(5, actual.getHardware());
        assertEquals(6, actual.getLlVersion());
        assertEquals(2, actual.getPatch());
        assertEquals(45, actual.getProtocolVersion());
    }

    @Test
    public void testBgStartScanning() throws Exception {
        BlueGigaDiscoverResponse discoverResponse = mock(BlueGigaDiscoverResponse.class);
        when(discoverResponse.getResult()).thenReturn(BgApiResponse.SUCCESS);
        mockTransaction(BlueGigaSetScanParametersCommand.class, mock(BlueGigaResponse.class));
        mockTransaction(BlueGigaDiscoverCommand.class, discoverResponse);

        assertTrue(handler.bgStartScanning());
    }

    @Test
    public void testBgGetRssi() throws Exception {
        BlueGigaGetRssiResponse response = mock(BlueGigaGetRssiResponse.class);
        when(response.getRssi()).thenReturn(-77);
        mockTransaction(BlueGigaGetRssiCommand.class, response);

        assertEquals(-77, handler.bgGetRssi(CONNECTION_HANDLE));
    }

    @Test
    public void testBgStopProcedure() throws Exception {
        BlueGigaEndProcedureResponse response = mock(BlueGigaEndProcedureResponse.class);
        when(response.getResult()).thenReturn(BgApiResponse.SUCCESS);
        mockTransaction(BlueGigaEndProcedureCommand.class, response);

        assertTrue(handler.bgStopProcedure());
    }

    @Test
    public void testDispose() throws Exception {
        when(bgHandler.isAlive()).thenReturn(true);
        BlueGigaEndProcedureResponse endProcedureResponse = mock(BlueGigaEndProcedureResponse.class);
        when(endProcedureResponse.getResult()).thenReturn(BgApiResponse.SUCCESS);
        when(bgHandler.sendTransaction(isA(BlueGigaEndProcedureCommand.class),
                eq(BlueGigaEndProcedureResponse.class), anyLong())).thenReturn(endProcedureResponse);

        BlueGigaGetConnectionsResponse connectionsResponse = mock(BlueGigaGetConnectionsResponse.class);
        when(connectionsResponse.getMaxconn()).thenReturn(3);
        when(bgHandler.sendTransaction(isA(BlueGigaGetConnectionsCommand.class),
                eq(BlueGigaGetConnectionsResponse.class), anyLong())).thenReturn(connectionsResponse);

        BlueGigaDisconnectResponse disconnectResponse = mock(BlueGigaDisconnectResponse.class);
        when(disconnectResponse.getResult()).thenReturn(BgApiResponse.SUCCESS);
        when(bgHandler.sendTransaction(isA(BlueGigaDisconnectCommand.class),
                eq(BlueGigaDisconnectResponse.class), anyLong())).thenReturn(disconnectResponse);

        handler.dispose();

        verify(bgHandler).sendTransaction(isA(BlueGigaEndProcedureCommand.class),
                eq(BlueGigaEndProcedureResponse.class), anyLong());
        verify(bgHandler).sendTransaction(isA(BlueGigaGetConnectionsCommand.class),
                eq(BlueGigaGetConnectionsResponse.class), anyLong());
        verify(bgHandler, times(3)).sendTransaction(isA(BlueGigaDisconnectCommand.class),
                eq(BlueGigaDisconnectResponse.class), anyLong());
        verify(bgHandler).close(anyLong());
    }

    @Test(expected = BlueGigaException.class)
    public void testSendTransactionException() throws Exception {
        when(bgHandler.isAlive()).thenReturn(true);
        when(bgHandler.sendTransaction(any(), any(), anyLong())).thenThrow(RuntimeException.class);

        handler.bgGetInfo();
    }

    @Test(expected = BluegigaException.class)
    public void testSyncCallExceptionBadResponse() throws Exception {
        mockConnect(BgApiResponse.INVALID_COMMAND, CONNECTION_HANDLE, DEVICE_URL);
    }

    @Test(expected = BluegigaException.class)
    public void testSyncCallExceptionTimeout() throws Exception {
        Whitebox.setInternalState(handler, "eventWaitTimeout", 10L);
        mockSyncProcedure(BlueGigaDisconnectCommand.class, BlueGigaDisconnectResponse.class,
            BgApiResponse.SUCCESS, CONNECTION_HANDLE);
        handler.disconnect(CONNECTION_HANDLE);
    }

    @Test(expected = BluegigaException.class)
    public void testSyncCallInterruptException() throws Exception {
        mockSyncProcedure(BlueGigaDisconnectCommand.class, BlueGigaDisconnectResponse.class,
            BgApiResponse.SUCCESS, CONNECTION_HANDLE);
        Thread current = Thread.currentThread();
        Executors.newSingleThreadScheduledExecutor().schedule(current::interrupt, 100, TimeUnit.MILLISECONDS);
        handler.disconnect(CONNECTION_HANDLE);
    }

    @Test(expected = BluegigaException.class)
    public void testSyncProcedureCallExceptionBadResponse() throws Exception {
        mockSyncProcedure(BlueGigaReadByGroupTypeCommand.class, BlueGigaReadByGroupTypeResponse.class,
            BgApiResponse.INVALID_COMMAND, CONNECTION_HANDLE);
        handler.getServices(CONNECTION_HANDLE);
    }

    @Test(expected = BluegigaException.class)
    public void testSyncProcedureCallInterruptedException() throws Exception {
        mockSyncProcedure(BlueGigaReadByGroupTypeCommand.class, BlueGigaReadByGroupTypeResponse.class,
            BgApiResponse.SUCCESS, CONNECTION_HANDLE);
        Thread current = Thread.currentThread();
        Executors.newSingleThreadScheduledExecutor().schedule(current::interrupt, 100, TimeUnit.MILLISECONDS);
        handler.getServices(CONNECTION_HANDLE);
    }

    @Test(expected = BluegigaException.class)
    public void testSyncProcedureCallExceptionTimeout() throws Exception {
        Whitebox.setInternalState(handler, "eventWaitTimeout", 10L);
        mockAsyncMultiEventProcedure(BlueGigaReadByGroupTypeCommand.class, BlueGigaReadByGroupTypeResponse.class,
            mockEvent(BlueGigaGroupFoundEvent.class, CONNECTION_HANDLE)
        //    mockEvent(BlueGigaProcedureCompletedEvent.class, CONNECTION_HANDLE)
        );
        handler.getServices(CONNECTION_HANDLE);
    }

    private static void mockAndScheduleEvent(Runnable runnable) {
        Executors.newSingleThreadScheduledExecutor().schedule(runnable, 100, TimeUnit.MILLISECONDS);
    }

    private <T extends BlueGigaResponse> void mockTransaction(Class<? extends BlueGigaCommand> requestClass,
                                 T response) throws TimeoutException {
        when(bgHandler.sendTransaction(isA(requestClass), any(), anyLong())).thenReturn(response);
    }

    private BlueGigaConnectionStatusEvent mockConnect(BgApiResponse response, int connectionHandle, URL url)
        throws Exception {
        mockAsyncProcedure(BlueGigaConnectDirectCommand.class,
            BlueGigaConnectDirectResponse.class,
            BlueGigaConnectionStatusEvent.class, response, connectionHandle, url.getDeviceAddress());
        return handler.connect(url, BluetoothAddressType.UNKNOWN);
    }

    private BlueGigaDisconnectedEvent mockDisconnect(BgApiResponse response, int connectionHandle)
        throws Exception {
        mockAsyncProcedure(BlueGigaDisconnectCommand.class,
            BlueGigaDisconnectResponse.class, BlueGigaDisconnectedEvent.class, response,
            connectionHandle, null);
        return handler.disconnect(connectionHandle);
    }

    private void mockStartStopDiscovery(BgApiResponse startResponse, BgApiResponse stopResponse)
        throws TimeoutException {
        mockSyncProcedure(BlueGigaSetScanParametersCommand.class, BlueGigaResponse.class, BgApiResponse.SUCCESS,-1);
        mockSyncProcedure(BlueGigaDiscoverCommand.class, BlueGigaDiscoverResponse.class, startResponse,-1);
        mockSyncProcedure(BlueGigaEndProcedureCommand.class, BlueGigaEndProcedureResponse.class, stopResponse,-1);
    }

    private <C extends BlueGigaCommand, R extends BlueGigaResponse> void mockSyncProcedure(
        Class<C> commandClass, Class<R> repoonseClass,
        BgApiResponse responseResult, int connectionHandle)
        throws TimeoutException {
        R response = mock(repoonseClass);
        tryMock(response, "getResult", responseResult);
        tryMock(response, "getConnectionHandle", connectionHandle);
        mockTransaction(commandClass, response);
    }

    private <C extends BlueGigaCommand, R extends BlueGigaResponse> void mockSyncProcedure(
        Class<C> commandClass, R response,
        BgApiResponse responseResult, int connectionHandle)
        throws TimeoutException {
        tryMock(response, "getResult", responseResult);
        tryMock(response, "getConnectionHandle", connectionHandle);
        mockTransaction(commandClass, response);
    }

    private <C extends BlueGigaCommand, R extends BlueGigaResponse, E extends BlueGigaResponse> void mockAsyncProcedure(
        Class<C> commandClass, Class<R> responseClass, BgApiResponse responseResult, int connectionHandle, E event)
        throws Exception {
        mockSyncProcedure(commandClass, responseClass, responseResult, connectionHandle);
        mockAndScheduleEvent(() -> {
            tryMock(event, "getConnection", connectionHandle);
            handler.bluegigaEventReceived(event);
        });
    }

    private <C extends BlueGigaCommand, R extends BlueGigaResponse, E extends BlueGigaResponse> void mockAsyncProcedure(
        Class<C> commandClass, Class<R> responseClass, Class<E> eventClass,
        BgApiResponse responseResult, int connectionHandle, String address)
        throws Exception {
        mockSyncProcedure(commandClass, responseClass, responseResult, connectionHandle);
        mockAndScheduleEvent(() -> {
            E event = mock(eventClass);
            tryMock(event, "getConnection", connectionHandle);
            tryMock(event, "getAddress", address);
            handler.bluegigaEventReceived(event);
        });
    }

    private <C extends BlueGigaCommand, R extends BlueGigaResponse,
        F extends BlueGigaResponse> void mockAsyncMultiEventProcedure(
        Class<C> commandClass, Class<R> responseClass, F... events) throws Exception {
        mockSyncProcedure(commandClass, responseClass, BgApiResponse.SUCCESS, CONNECTION_HANDLE);
        mockAndScheduleEvent(() -> {
            Stream.of(events).forEach(handler::bluegigaEventReceived);
        });
    }

    private <E extends BlueGigaResponse> E mockEvent(Class<E> eventClass, int connectionHandle) {
        return mockEvent(eventClass, connectionHandle, null);
    }

    private <E extends BlueGigaResponse> E mockEvent(Class<E> eventClass, int connectionHandle,
                                                     AttributeValueType attributeValueType) {
        E event = mock(eventClass);
        tryMock(event, "getConnection", connectionHandle);
        tryMock(event, "getAddress", adapterAddress);
        tryMock(event, "getType", attributeValueType);
        return event;
    }

    private void tryMock(Object obj, String methodName, Object response) {
        try {
            when(Whitebox.invokeMethod(obj, methodName)).thenReturn(response);
        } catch (Exception ignore) { }
    }

}