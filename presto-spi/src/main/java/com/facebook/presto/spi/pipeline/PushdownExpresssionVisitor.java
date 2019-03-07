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

public abstract class PushdownExpresssionVisitor<R, C>
{
    public R visitExpression(PushdownExpression expression, C context)
    {
        throw new UnsupportedOperationException();
    }

    public R visitInputColumn(PushdownInputColumn inputColumn, C context)
    {
        return visitExpression(inputColumn, context);
    }

    public R visitFunction(PushdownFunction function, C context)
    {
        return visitExpression(function, context);
    }

    public R visitLogicalBinary(PushdownLogicalBinaryExpression comparision, C context)
    {
        return visitExpression(comparision, context);
    }

    public R visitInExpression(PushdownInExpression in, C context)
    {
        return visitExpression(in, context);
    }

    public R visitLiteral(PushdownLiteral literal, C context)
    {
        return visitExpression(literal, context);
    }
}
