/*
 * Copyright 2021 German Vekhorev (DarksideCode)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.darksidecode.keiko.config;

import lombok.Getter;
import org.bukkit.configuration.file.YamlConfiguration;

public final class InspectionsConfig {

    private InspectionsConfig() {}

    @Getter
    private static YamlConfiguration yaml;

    @Getter @Config (path = "static.caches_lifespan_days")
    private static Integer cachesLifespanDays = 7;

    @Getter @Config (path = "plugins_integrity.abort_server_startup")
    private static Boolean abortServerStartupOnIntegrityViolation = true;

}
