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
package com.facebook.presto.spi.pipeline;

import com.facebook.presto.spi.type.Type;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "@type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TablePipelineNode.class, name = "table"),
        @JsonSubTypes.Type(value = ProjectPipelineNode.class, name = "project"),
        @JsonSubTypes.Type(value = FilterPipelineNode.class, name = "filter"),
        @JsonSubTypes.Type(value = AggregationPipelineNode.class, name = "aggregation")})
public abstract class PipelineNode
{
    public abstract PipelineType getType();

    public abstract List<String> getOutputColumns();

    public abstract List<Type> getRowType();

    public PipelineNode getSource()
    {
        return null;
    }

    public <R, C> R accept(TableScanPipelineVisitor<R, C> visitor, C context)
    {
        return visitor.visitNode(this, context);
    }

    public enum PipelineType
    {
        TABLE,
        PROJECT,
        FILTER,
        AGGREGATION
    }
}
