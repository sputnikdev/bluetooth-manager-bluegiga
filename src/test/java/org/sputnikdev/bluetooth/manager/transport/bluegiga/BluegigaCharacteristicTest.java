package org.sputnikdev.bluetooth.manager.transport.bluegiga;

import com.zsmartsystems.bluetooth.bluegiga.BlueGigaResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaAttributeValueEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaProcedureCompletedEvent;
import com.zsmartsystems.bluetooth.bluegiga.enumeration.BgApiResponse;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.transport.CharacteristicAccessType;
import org.sputnikdev.bluetooth.manager.transport.Notification;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyVararg;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;


@RunWith(MockitoJUnitRunner.class)
public class BluegigaCharacteristicTest {

    private static final URL CHARACTERISTIC_URL = new URL("/12:34:56:78:90:12/11:22:33:44:55:66/" +
            "0000180f-0000-1000-8000-00805f9b34fb/00002a19-0000-1000-8000-00805f9b34fb");
    private static final int CONNECTION_HANDLE = 1;
    private static final int CHARACTERISTIC_HANDLE = 2;
    private static final Set<CharacteristicAccessType> FLAGS =
        EnumSet.of(CharacteristicAccessType.NOTIFY, CharacteristicAccessType.READ);

    @Mock
    private BluegigaHandler bluegigaHandler;
    @Mock
    private BluegigaDescriptor notificationDescriptor;

    private BluegigaCharacteristic characteristic;

    @Before
    public void setUp() {
        characteristic = spy(new BluegigaCharacteristic(bluegigaHandler, CHARACTERISTIC_URL,
            CONNECTION_HANDLE, CHARACTERISTIC_HANDLE));

        when(notificationDescriptor.getUuid()).thenReturn(UUID.fromString("00002902-0000-0000-0000-000000000000"));
    }

    @Test
    public void testGetSetFlags() throws Exception {
        assertEquals(0, characteristic.getFlags().size());
        characteristic.setFlags(FLAGS);
        assertEquals(FLAGS, characteristic.getFlags());
    }

    @Test
    public void testIsNotifying() throws Exception {
        assertFalse(characteristic.isNotifying());

        // if no descriptor is present, this means that notification is enabled by default and cannot be disabled
        characteristic.setFlags(FLAGS);
        assertTrue(characteristic.isNotifying());

        characteristic.addDescriptor(notificationDescriptor);
        when(notificationDescriptor.readValue()).thenReturn(new byte[] {0b0});
        assertFalse(characteristic.isNotifying());

        when(notificationDescriptor.readValue()).thenReturn(new byte[] {0b1});
        assertTrue(characteristic.isNotifying());

        when(notificationDescriptor.readValue()).thenReturn(new byte[] {0b10});
        assertTrue(characteristic.isNotifying());

        when(notificationDescriptor.readValue()).thenReturn(new byte[] {0b11});
        assertTrue(characteristic.isNotifying());

        when(notificationDescriptor.readValue()).thenReturn(new byte[] {0b100});
        assertFalse(characteristic.isNotifying());
    }

    @Test
    public void readValue() throws Exception {
        int[] data = {12, 34};
        BlueGigaAttributeValueEvent event = mock(BlueGigaAttributeValueEvent.class);
        when(event.getValue()).thenReturn(data);

        when(bluegigaHandler.readCharacteristic(CONNECTION_HANDLE, CHARACTERISTIC_HANDLE)).thenReturn(event);

        assertArrayEquals(new byte[] {12, 34}, characteristic.readValue());

        verify(bluegigaHandler).readCharacteristic(CONNECTION_HANDLE, CHARACTERISTIC_HANDLE);
    }

    @Test
    public void writeValueWithoutResonse() throws Exception {
        byte[] byteData = {12, 34};
        int[] intData = {12, 34};

        characteristic.setFlags(EnumSet.of(CharacteristicAccessType.WRITE_WITHOUT_RESPONSE));

        when(bluegigaHandler.writeCharacteristicWithoutResponse(
            CONNECTION_HANDLE, CHARACTERISTIC_HANDLE, intData)).thenReturn(true);

        assertTrue(characteristic.writeValue(byteData));

        when(bluegigaHandler.writeCharacteristicWithoutResponse(
            CONNECTION_HANDLE, CHARACTERISTIC_HANDLE, intData)).thenReturn(false);

        assertFalse(characteristic.writeValue(byteData));

        verify(bluegigaHandler, times(2))
            .writeCharacteristicWithoutResponse(CONNECTION_HANDLE, CHARACTERISTIC_HANDLE, intData);
    }

    @Test
    public void writeValueWithResonse() throws Exception {
        byte[] byteData = {12, 34};
        int[] intData = {12, 34};

        characteristic.setFlags(EnumSet.of(CharacteristicAccessType.WRITE));

        BlueGigaProcedureCompletedEvent event = mock(BlueGigaProcedureCompletedEvent.class);
        when(event.getResult()).thenReturn(BgApiResponse.SUCCESS);

        when(bluegigaHandler.writeCharacteristic(
            CONNECTION_HANDLE, CHARACTERISTIC_HANDLE, intData)).thenReturn(event);

        assertTrue(characteristic.writeValue(byteData));

        when(event.getResult()).thenReturn(BgApiResponse.TIMEOUT);

        assertFalse(characteristic.writeValue(byteData));

        verify(bluegigaHandler, times(2))
            .writeCharacteristic(CONNECTION_HANDLE, CHARACTERISTIC_HANDLE, intData);
    }

    @Test
    public void testToggleNotification() throws Exception {
        characteristic.addDescriptor(notificationDescriptor);
        when(notificationDescriptor.writeValue(anyVararg())).thenReturn(true);

        characteristic.setFlags(EnumSet.of(CharacteristicAccessType.NOTIFY));
        characteristic.toggleNotification(true);
        verify(notificationDescriptor).writeValue(new byte[] {0b01, 0b0});

        characteristic.setFlags(EnumSet.of(CharacteristicAccessType.INDICATE));
        characteristic.toggleNotification(true);
        verify(notificationDescriptor).writeValue(new byte[] {0b10, 0b0});
    }

    @Test
    public void testToggleNotificationNoFlags() throws Exception {
        characteristic.addDescriptor(notificationDescriptor);

        characteristic.toggleNotification(true);

        verify(notificationDescriptor, never()).writeValue(anyVararg());
    }

    @Test
    public void testToggleNotificationNoDescriptor() throws Exception {
        // if no descriptor is present, this means that notification is enabled by default and cannot be disabled
        characteristic.setFlags(EnumSet.of(CharacteristicAccessType.NOTIFY));

        // check if it is does not fail with any error
        characteristic.toggleNotification(false);
        assertTrue(true);
    }

    @Test
    public void testIsNotificationConfigurable() {
        assertFalse(characteristic.isNotificationConfigurable());

        characteristic.setFlags(EnumSet.of(CharacteristicAccessType.NOTIFY));
        assertFalse(characteristic.isNotificationConfigurable());

        characteristic.addDescriptor(notificationDescriptor);
        assertTrue(characteristic.isNotificationConfigurable());
    }

    @Test(expected = BluegigaException.class)
    public void testToggleNotificationFailureResponse() throws Exception {
        characteristic.addDescriptor(notificationDescriptor);
        when(notificationDescriptor.writeValue(anyVararg())).thenReturn(false);
        characteristic.setFlags(EnumSet.of(CharacteristicAccessType.NOTIFY));
        characteristic.toggleNotification(true);
    }

    @Test
    public void testEnableDisableNotification() {
        int[] intData = {1, 2, 3};
        byte[] byteData = {1, 2, 3};
        Notification<byte[]> notification = (Notification<byte[]>) mock(Notification.class);

        doNothing().when(characteristic).toggleNotification(anyBoolean());

        characteristic.bluegigaEventReceived(mockValueEvent(CONNECTION_HANDLE, CHARACTERISTIC_HANDLE, intData));

        characteristic.enableValueNotifications(notification);
        verify(characteristic).toggleNotification(true);

        characteristic.bluegigaEventReceived(mockValueEvent(2, CHARACTERISTIC_HANDLE, intData));
        verify(notification, never()).notify(byteData);

        characteristic.bluegigaEventReceived(mockValueEvent(CONNECTION_HANDLE, 3, intData));
        verify(notification, never()).notify(byteData);

        characteristic.bluegigaEventReceived(mockValueEvent(CONNECTION_HANDLE, CHARACTERISTIC_HANDLE, intData));
        verify(notification, times(1)).notify(byteData);

        characteristic.bluegigaEventReceived(mock(BlueGigaResponse.class));

        doThrow(RuntimeException.class).when(notification).notify(anyVararg());
        characteristic.bluegigaEventReceived(mockValueEvent(CONNECTION_HANDLE, CHARACTERISTIC_HANDLE, intData));
        verify(notification, times(2)).notify(byteData);

        characteristic.disableValueNotifications();
        verify(characteristic).toggleNotification(false);
        characteristic.bluegigaEventReceived(mockValueEvent(CONNECTION_HANDLE, CHARACTERISTIC_HANDLE, intData));
        verify(notification, times(2)).notify(byteData);
    }

    @Test
    public void getURL() throws Exception {
        assertEquals(CHARACTERISTIC_URL, characteristic.getURL());
    }

    @Test
    public void getCharacteristicHandle() throws Exception {
        assertEquals(CHARACTERISTIC_HANDLE, characteristic.getCharacteristicHandle());
    }

    @Test
    public void bluegigaEventReceived() throws Exception {
        int[] intData = {1, 2, 3};
        byte[] byteData = {1, 2, 3};
        Notification<byte[]> notification = (Notification<byte[]>) mock(Notification.class);
        doNothing().when(characteristic).toggleNotification(anyBoolean());

        characteristic.bluegigaEventReceived(mockValueEvent(CONNECTION_HANDLE, CHARACTERISTIC_HANDLE, intData));
        verify(notification, never()).notify(byteData);

        characteristic.enableValueNotifications(notification);

        characteristic.bluegigaEventReceived(mockValueEvent(2, CHARACTERISTIC_HANDLE, intData));
        verify(notification, never()).notify(byteData);

        characteristic.bluegigaEventReceived(mockValueEvent(CONNECTION_HANDLE, 3, intData));
        verify(notification, never()).notify(byteData);

        characteristic.bluegigaEventReceived(mockValueEvent(CONNECTION_HANDLE, CHARACTERISTIC_HANDLE, intData));
        verify(notification, times(1)).notify(byteData);

        characteristic.bluegigaEventReceived(mock(BlueGigaResponse.class));
        verify(notification, times(1)).notify(byteData);

        doThrow(RuntimeException.class).when(notification).notify(anyVararg());
        characteristic.bluegigaEventReceived(mockValueEvent(CONNECTION_HANDLE, CHARACTERISTIC_HANDLE, intData));
        verify(notification, times(2)).notify(byteData);

        characteristic.disableValueNotifications();
        characteristic.bluegigaEventReceived(mockValueEvent(CONNECTION_HANDLE, CHARACTERISTIC_HANDLE, intData));
        verify(notification, times(2)).notify(byteData);
    }

    @Test
    public void addGetDescriptor() throws Exception {
        assertTrue(characteristic.getDescriptors().isEmpty());
        characteristic.addDescriptor(notificationDescriptor);
        assertEquals(1, characteristic.getDescriptors().size());
        assertEquals(notificationDescriptor, characteristic.getDescriptors().iterator().next());
    }

    private BlueGigaAttributeValueEvent mockValueEvent(int connectionHandle, int characteristicHandle, int[] data) {
        BlueGigaAttributeValueEvent event = mock(BlueGigaAttributeValueEvent.class);
        when(event.getValue()).thenReturn(data);
        when(event.getConnection()).thenReturn(connectionHandle);
        when(event.getAttHandle()).thenReturn(characteristicHandle);
        return event;
    }

}