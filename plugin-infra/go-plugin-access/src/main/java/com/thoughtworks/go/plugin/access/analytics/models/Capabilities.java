/*
 * Copyright 2017 ThoughtWorks, Inc.
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

package com.thoughtworks.go.plugin.access.analytics.models;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class Capabilities {
    private static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();

    @Expose
    @SerializedName("supports_pipeline_analytics")
    private final boolean supportsPipelineAnalytics;

    @Expose
    @SerializedName("supports_analytics_dashboard")
    private final boolean supportsAnalyticsDashboard;

    public Capabilities(boolean supportsPipelineAnalytics, boolean supportsAnalyticsDashboard) {
        this.supportsPipelineAnalytics = supportsPipelineAnalytics;
        this.supportsAnalyticsDashboard = supportsAnalyticsDashboard;
    }

    public boolean supportsAnalyticsDashboard() {
        return supportsAnalyticsDashboard;
    }

    public boolean supportsPipelineAnalytics() {
        return supportsPipelineAnalytics;
    }

    public String toJSON() {
        return GSON.toJson(this);
    }

    public static Capabilities fromJSON(String json) {
        return GSON.fromJson(json, Capabilities.class);
    }

    public com.thoughtworks.go.plugin.domain.analytics.Capabilities toCapabilities() {
        return new com.thoughtworks.go.plugin.domain.analytics.Capabilities(supportsPipelineAnalytics, supportsAnalyticsDashboard);
    }
}