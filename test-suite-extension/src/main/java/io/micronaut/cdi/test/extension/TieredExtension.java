/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.cdi.test.extension;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;

/**
 * Adds two ledgers told apart only by the enum member of their qualifier, one of them named as well.
 */
public final class TieredExtension implements BuildCompatibleExtension {

    /**
     * The synthesis.
     *
     * @param components Where the beans go
     */
    @Synthesis
    public void addLedgers(SyntheticComponents components) {
        components.addBean(Ledger.class)
            .type(Ledger.class)
            .qualifier(new Tier.Literal(Tier.Level.GOLD))
            .withParam("tier", "gold")
            .createWith(LedgerCreator.class);
        components.addBean(Ledger.class)
            .type(Ledger.class)
            .qualifier(new Tier.Literal(Tier.Level.SILVER))
            .name("silver-ledger")
            .withParam("tier", "silver")
            .createWith(LedgerCreator.class);
    }

    /**
     * Creates a ledger for the tier it was declared with.
     */
    public static final class LedgerCreator implements SyntheticBeanCreator<Ledger> {
        @Override
        public Ledger create(Instance<Object> lookup, Parameters params) {
            return new Ledger(params.get("tier", String.class));
        }
    }
}
