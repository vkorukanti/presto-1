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
package io.prestosql.spi.type;

import io.prestosql.spi.PrestoException;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.connector.ConnectorSession;

import static io.prestosql.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.prestosql.spi.type.TypeSignature.parseTypeSignature;

public final class IntegerType
        extends AbstractIntType
{
    public static final IntegerType INTEGER = new IntegerType();

    private IntegerType()
    {
        super(parseTypeSignature(StandardTypes.INTEGER));
    }

    @Override
    public Object getObjectValue(ConnectorSession session, Block block, int position)
    {
        if (block.isNull(position)) {
            return null;
        }

        return block.getInt(position, 0);
    }

    @Override
    public final void writeLong(BlockBuilder blockBuilder, long value)
    {
        if (value > Integer.MAX_VALUE) {
            throw new PrestoException(GENERIC_INTERNAL_ERROR, String.format("Value %d exceeds MAX_INT", value));
        }
        else if (value < Integer.MIN_VALUE) {
            throw new PrestoException(GENERIC_INTERNAL_ERROR, String.format("Value %d is less than MIN_INT", value));
        }

        blockBuilder.writeInt((int) value).closeEntry();
    }

    @Override
    @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
    public boolean equals(Object other)
    {
        return other == INTEGER;
    }

    @Override
    public int hashCode()
    {
        return getClass().hashCode();
    }
}
