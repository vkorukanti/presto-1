/*
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
package com.facebook.presto.pinot;

import com.facebook.presto.spi.ConnectorSplit;
import com.facebook.presto.spi.HostAddress;
import com.facebook.presto.spi.pipeline.TableScanPipeline;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableMap;

import java.util.List;

import static java.util.Objects.requireNonNull;

public class PinotPushedDownQuerySplit
        implements ConnectorSplit
{
    private final String connectorId;
    private final TableScanPipeline scanPipeline;

    @JsonCreator
    public PinotPushedDownQuerySplit(
            @JsonProperty("connectorId") String connectorId,
            @JsonProperty("scanPipeline") TableScanPipeline scanPipeline)
    {
        this.connectorId = requireNonNull(connectorId, "connectorId is null");
        this.scanPipeline = requireNonNull(scanPipeline, "scanPipeline is null");
    }

    @JsonProperty
    public TableScanPipeline getScanPipeline()
    {
        return scanPipeline;
    }

    @JsonProperty
    public String getConnectorId()
    {
        return connectorId;
    }

    @Override
    public boolean isRemotelyAccessible()
    {
        return true;
    }

    @Override
    public List<HostAddress> getAddresses()
    {
        return null;
    }

    @Override
    public Object getInfo()
    {
        return ImmutableMap.builder()
                .put("scanPipeline", scanPipeline)
                .build();
    }
}
