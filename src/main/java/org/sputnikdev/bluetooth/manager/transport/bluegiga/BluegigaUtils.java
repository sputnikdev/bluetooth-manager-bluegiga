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

import com.zsmartsystems.bluetooth.bluegiga.BlueGigaResponse;

import java.util.UUID;

/**
 * Utility methods to work with Bluegiga specific logic.
 * @author Vlad Kolotov
 * @author Chris Jackson
 */
final class BluegigaUtils {

    private BluegigaUtils() { }

    public static byte[] fromInts(int[] data) {
        byte[] bytes = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            bytes[i] = (byte) data[i];
        }
        return bytes;
    }

    public static int[] fromBytes(byte[] data) {
        int[] bytes = new int[data.length];
        for (int i = 0; i < data.length; i++) {
            bytes[i] = data[i];
        }
        return bytes;
    }

    /**
     * Deserialises into UUID object from an array of integers.
     * Copied from {@link BlueGigaResponse#deserializeUuid()}
     * @param buffer some data represents UUID in bluegiga format
     * @return UUID
     */
    public static UUID deserializeUUID(int[] buffer) {
        long low;
        long high;
        int position = 0;
        int length = buffer.length;
        switch (length) {
            case 2:
                low = 0;
                high = ((long) buffer[position++] << 32) + ((long) buffer[position++] << 40);
                break;
            case 4:
                low = 0;
                high = ((long) buffer[position++] << 32) + ((long) buffer[position++] << 40)
                        + ((long) buffer[position++] << 48) + ((long) buffer[position++] << 56);
                break;
            case 16:
                low = (buffer[position++]) + ((long) buffer[position++] << 8) + ((long) buffer[position++] << 16)
                        + ((long) buffer[position++] << 24) + ((long) buffer[position++] << 32)
                        + ((long) buffer[position++] << 40) + ((long) buffer[position++] << 48)
                        + ((long) buffer[position++] << 56);
                high = (buffer[position++]) + ((long) buffer[position++] << 8) + ((long) buffer[position++] << 16)
                        + ((long) buffer[position++] << 24) + ((long) buffer[position++] << 32)
                        + ((long) buffer[position++] << 40) + ((long) buffer[position++] << 48)
                        + ((long) buffer[position++] << 56);
                break;
            default:
                low = 0;
                high = 0;
                break;
        }
        return new UUID(high, low);
    }

}
