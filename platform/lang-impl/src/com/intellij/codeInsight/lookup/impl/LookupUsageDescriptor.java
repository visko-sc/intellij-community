// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInsight.lookup.impl;

import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.internal.statistic.eventLog.FeatureUsageData;
import com.intellij.internal.statistic.eventLog.events.EventPair;
import com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/**
 * Allows to extend information in fus logs about shown lookup.
 * <p>
 * Each update in values we sent should be reflected in white-list scheme for `finished` event in `completion` group.
 * <p>
 * see {@link LookupUsageTracker}
 */
@ApiStatus.Internal
public interface LookupUsageDescriptor {
  ExtensionPointName<LookupUsageDescriptor> EP_NAME = ExtensionPointName.create("com.intellij.lookup.usageDetails");

  /**
   * @return key of extension inside {@link FeatureUsageData} of `completion.finished` event
   */
  @NotNull
  String getExtensionKey();

  /**
   * @deprecated use {@link LookupUsageDescriptor#getAdditionalUsageData(com.intellij.codeInsight.lookup.Lookup)}
   */
  @Deprecated
  default void fillUsageData(@NotNull Lookup lookup, @NotNull FeatureUsageData usageData) {
    getAdditionalUsageData(lookup).forEach(pair -> pair.addData(usageData));
  }

  /**
   * The method is triggered after the lookup usage finishes. Use it to fill usageData with information to collect.
   */
  default List<EventPair<?>> getAdditionalUsageData(@NotNull Lookup lookup) {
    return Collections.emptyList();
  }
}
