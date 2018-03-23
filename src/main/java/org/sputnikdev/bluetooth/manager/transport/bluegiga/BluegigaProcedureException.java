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

import com.zsmartsystems.bluetooth.bluegiga.enumeration.BgApiResponse;

/**
 * An exception that describes Bluegiga API procedure exceptions.
 * @author Vlad Kolotov
 */
public class BluegigaProcedureException extends BluegigaException {

    private final BgApiResponse response;

    public BluegigaProcedureException(String message, BgApiResponse response) {
        super(message);
        this.response = response;
    }

    public BgApiResponse getResponse() {
        return response;
    }
}
