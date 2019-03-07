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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

public class PushdownFunction
        extends PushdownExpression
{
    private final String name;
    private final List<PushdownExpression> inputs;

    @JsonCreator
    public PushdownFunction(
            @JsonProperty("name") String name,
            @JsonProperty("inputs") List<PushdownExpression> inputs)
    {
        this.name = requireNonNull(name, "name is null");
        this.inputs = requireNonNull(inputs, "inputs is null");
    }

    @JsonProperty
    public String getName()
    {
        return name;
    }

    @JsonProperty
    public List<PushdownExpression> getInputs()
    {
        return inputs;
    }

    public <R, C> R accept(PushdownExpresssionVisitor<R, C> visitor, C context)
    {
        return visitor.visitFunction(this, context);
    }

    @Override
    public String toString()
    {
        return name + "(" + inputs.stream().map(e -> e.toString()).collect(Collectors.joining(", ")) + ")";
    }
}
