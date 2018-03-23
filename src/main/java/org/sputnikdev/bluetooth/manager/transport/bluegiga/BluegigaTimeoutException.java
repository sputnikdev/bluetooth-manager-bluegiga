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

/**
 * An exception that describes Bluegiga API interaction timeouts.
 * @author Vlad Kolotov
 */
public class BluegigaTimeoutException extends BluegigaException {

    public BluegigaTimeoutException(String message) {
        super(message);
    }

    public BluegigaTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }

}
