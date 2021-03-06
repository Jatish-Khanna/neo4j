/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.index.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

import org.neo4j.io.pagecache.ByteArrayPageCursor;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.index.schema.config.ConfiguredSpaceFillingCurveSettingsCache;
import org.neo4j.kernel.impl.index.schema.config.IndexSpecificSpaceFillingCurveSettingsCache;
import org.neo4j.string.UTF8;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.RandomExtension;
import org.neo4j.test.rule.RandomRule;
import org.neo4j.values.storable.DateTimeValue;
import org.neo4j.values.storable.DateValue;
import org.neo4j.values.storable.DurationValue;
import org.neo4j.values.storable.LocalDateTimeValue;
import org.neo4j.values.storable.LocalTimeValue;
import org.neo4j.values.storable.PointValue;
import org.neo4j.values.storable.RandomValues;
import org.neo4j.values.storable.TimeValue;
import org.neo4j.values.storable.Value;
import org.neo4j.values.storable.ValueGroup;
import org.neo4j.values.storable.Values;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.neo4j.kernel.impl.index.schema.NativeIndexKey.Inclusion.NEUTRAL;
import static org.neo4j.values.storable.Values.COMPARATOR;
import static org.neo4j.values.storable.Values.booleanArray;
import static org.neo4j.values.storable.Values.byteArray;
import static org.neo4j.values.storable.Values.dateArray;
import static org.neo4j.values.storable.Values.dateTimeArray;
import static org.neo4j.values.storable.Values.doubleArray;
import static org.neo4j.values.storable.Values.durationArray;
import static org.neo4j.values.storable.Values.floatArray;
import static org.neo4j.values.storable.Values.intArray;
import static org.neo4j.values.storable.Values.isGeometryArray;
import static org.neo4j.values.storable.Values.isGeometryValue;
import static org.neo4j.values.storable.Values.localDateTimeArray;
import static org.neo4j.values.storable.Values.localTimeArray;
import static org.neo4j.values.storable.Values.longArray;
import static org.neo4j.values.storable.Values.of;
import static org.neo4j.values.storable.Values.pointArray;
import static org.neo4j.values.storable.Values.shortArray;
import static org.neo4j.values.storable.Values.timeArray;

@ExtendWith( RandomExtension.class )
class GenericKeyStateTest
{
    private final IndexSpecificSpaceFillingCurveSettingsCache noSpecificIndexSettings =
            new IndexSpecificSpaceFillingCurveSettingsCache( new ConfiguredSpaceFillingCurveSettingsCache( Config.defaults() ), new HashMap<>() );

    @Inject
    private static RandomRule random;

    @BeforeEach
    void setupRandomConfig()
    {
        random = random.withConfiguration( new RandomValues.Configuration()
        {
            @Override
            public int stringMinLength()
            {
                return 0;
            }

            @Override
            public int stringMaxLength()
            {
                return 50;
            }

            @Override
            public int arrayMinLength()
            {
                return 0;
            }

            @Override
            public int arrayMaxLength()
            {
                return 10;
            }

            @Override
            public int maxCodePoint()
            {
                return RandomValues.MAX_BMP_CODE_POINT;
            }
        } );
        random.reset();
    }

    @ParameterizedTest
    @MethodSource( "validValueGenerators" )
    void readWhatIsWritten( ValueGenerator valueGenerator )
    {
        // Given
        PageCursor cursor = newPageCursor();
        GenericKeyState writeState = newKeyState();
        Value value = valueGenerator.next();
        int offset = cursor.getOffset();

        // When
        writeState.writeValue( value, NEUTRAL );
        writeState.put( cursor );

        // Then
        GenericKeyState readState = newKeyState();
        int size = writeState.size();
        cursor.setOffset( offset );
        assertTrue( readState.read( cursor, size ), "failed to read" );
        assertEquals( 0, readState.compareValueTo( writeState ), "key states are not equal" );
        Value readValue = readState.asValue();
        assertEquals( value, readValue, "deserialized values are not equal" );
    }

    @ParameterizedTest
    @MethodSource( "validValueGenerators" )
    void copyShouldCopy( ValueGenerator valueGenerator )
    {
        // Given
        GenericKeyState from = newKeyState();
        Value value = valueGenerator.next();
        from.writeValue( value, NEUTRAL );
        GenericKeyState to = genericKeyStateWithSomePreviousState( valueGenerator );

        // When
        to.copyFrom( from );

        // Then
        assertEquals( 0, from.compareValueTo( to ), "states not equals after copy" );
    }

    @Test
    void copyShouldCopyExtremeValues()
    {
        // Given
        GenericKeyState extreme = newKeyState();
        GenericKeyState copy = newKeyState();

        for ( ValueGroup valueGroup : ValueGroup.values() )
        {
            if ( valueGroup != ValueGroup.NO_VALUE )
            {
                extreme.initValueAsLowest( valueGroup );
                copy.copyFrom( extreme );
                assertEquals( 0, extreme.compareValueTo( copy ), "states not equals after copy, valueGroup=" + valueGroup );
                extreme.initValueAsHighest( valueGroup );
                copy.copyFrom( extreme );
                assertEquals( 0, extreme.compareValueTo( copy ), "states not equals after copy, valueGroup=" + valueGroup );
            }
        }
    }

    @ParameterizedTest
    @MethodSource( "validComparableValueGenerators" )
    void compareToMustAlignWithValuesCompareTo( ValueGenerator valueGenerator )
    {
        // Given
        random.reset();
        List<Value> values = new ArrayList<>();
        List<GenericKeyState> states = new ArrayList<>();
        for ( int i = 0; i < 10; i++ )
        {
            Value value = valueGenerator.next();
            values.add( value );
            GenericKeyState state = newKeyState();
            state.writeValue( value, NEUTRAL );
            states.add( state );
        }

        // When
        values.sort( COMPARATOR );
        states.sort( GenericKeyState::compareValueTo );

        // Then
        for ( int i = 0; i < values.size(); i++ )
        {
            assertEquals( values.get( i ), states.get( i ).asValue(), "sort order was different" );
        }
    }

    // The reason this test doesn't test incomparable values is that it relies on ordering being same as that of the Values module.
    @ParameterizedTest
    @MethodSource( "validComparableValueGenerators" )
    void mustProduceValidMinimalSplitters( ValueGenerator valueGenerator )
    {
        // Given
        Value value1 = valueGenerator.next();
        Value value2;
        do
        {
            value2 = valueGenerator.next();
        }
        while ( COMPARATOR.compare( value1, value2 ) == 0 );

        // When
        Value left = pickSmaller( value1, value2 );
        Value right = left == value1 ? value2 : value1;

        // Then
        assertValidMinimalSplitter( left, right );
    }

    @ParameterizedTest
    @MethodSource( "validValueGenerators" )
    void mustProduceValidMinimalSplittersWhenValuesAreEqual( ValueGenerator valueGenerator )
    {
        assertValidMinimalSplitterForEqualValues( valueGenerator.next() );
    }

    @ParameterizedTest
    @MethodSource( "validValueGenerators" )
    void mustReportCorrectSize( ValueGenerator valueGenerator )
    {
        // Given
        PageCursor cursor = newPageCursor();
        Value value = valueGenerator.next();
        GenericKeyState state = newKeyState();
        state.writeValue( value, NEUTRAL );
        int offsetBefore = cursor.getOffset();

        // When
        int reportedSize = state.size();
        state.put( cursor );
        int offsetAfter = cursor.getOffset();

        // Then
        int actualSize = offsetAfter - offsetBefore;
        assertEquals( reportedSize, actualSize,
                String.format( "did not report correct size, value=%s, actualSize=%d, reportedSize=%d", value, actualSize, reportedSize ) );
    }

    @Test
    void lowestMustBeLowest()
    {
        // GEOMETRY
        assertLowest( PointValue.MIN_VALUE );
        // ZONED_DATE_TIME
        assertLowest( DateTimeValue.MIN_VALUE );
        // LOCAL_DATE_TIME
        assertLowest( LocalDateTimeValue.MIN_VALUE );
        // DATE
        assertLowest( DateValue.MIN_VALUE );
        // ZONED_TIME
        assertLowest( TimeValue.MIN_VALUE );
        // LOCAL_TIME
        assertLowest( LocalTimeValue.MIN_VALUE );
        // DURATION (duration, period)
        assertLowest( DurationValue.duration( Duration.ofSeconds( Long.MIN_VALUE, 0 ) ) );
        assertLowest( DurationValue.duration( Period.of( Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE ) ) );
        // TEXT
        assertLowest( of( UTF8.decode( new byte[0] ) ) );
        // BOOLEAN
        assertLowest( of( false ) );
        // NUMBER (byte, short, int, long, float, double)
        assertLowest( of( Byte.MIN_VALUE ) );
        assertLowest( of( Short.MIN_VALUE ) );
        assertLowest( of( Integer.MIN_VALUE ) );
        assertLowest( of( Long.MIN_VALUE ) );
        assertLowest( of( Float.NEGATIVE_INFINITY ) );
        assertLowest( of( Double.NEGATIVE_INFINITY ) );
        // GEOMETRY_ARRAY
        assertLowest( pointArray( new PointValue[0] ) );
        // ZONED_DATE_TIME_ARRAY
        assertLowest( dateTimeArray( new ZonedDateTime[0] ) );
        // LOCAL_DATE_TIME_ARRAY
        assertLowest( localDateTimeArray( new LocalDateTime[0] ) );
        // DATE_ARRAY
        assertLowest( dateArray( new LocalDate[0] ) );
        // ZONED_TIME_ARRAY
        assertLowest( timeArray( new OffsetTime[0] ) );
        // LOCAL_TIME_ARRAY
        assertLowest( localTimeArray( new LocalTime[0] ) );
        // DURATION_ARRAY (DurationValue, TemporalAmount)
        assertLowest( durationArray( new DurationValue[0] ) );
        assertLowest( durationArray( new TemporalAmount[0] ) );
        // TEXT_ARRAY
        assertLowest( of( new String[0] ) );
        // BOOLEAN_ARRAY
        assertLowest( of( new boolean[0] ) );
        // NUMBER_ARRAY (byte[], short[], int[], long[], float[], double[])
        assertLowest( of( new byte[0] ) );
        assertLowest( of( new short[0] ) );
        assertLowest( of( new int[0] ) );
        assertLowest( of( new long[0] ) );
        assertLowest( of( new float[0] ) );
        assertLowest( of( new double[0] ) );
    }

    @Test
    void highestMustBeHighest()
    {
        // GEOMETRY
        assertHighest( PointValue.MAX_VALUE );
        // ZONED_DATE_TIME
        assertHighest( DateTimeValue.MAX_VALUE );
        // LOCAL_DATE_TIME
        assertHighest( LocalDateTimeValue.MAX_VALUE );
        // DATE
        assertHighest( DateValue.MAX_VALUE );
        // ZONED_TIME
        assertHighest( TimeValue.MAX_VALUE );
        // LOCAL_TIME
        assertHighest( LocalTimeValue.MAX_VALUE );
        // DURATION (duration, period)
        assertHighest( DurationValue.duration( Duration.ofSeconds( Long.MAX_VALUE, 999_999_999 ) ) );
        assertHighest( DurationValue.duration( Period.of( Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE ) ) );
        // TEXT
        assertHighestString();
        // BOOLEAN
        assertHighest( of( true ) );
        // NUMBER (byte, short, int, long, float, double)
        assertHighest( of( Byte.MAX_VALUE ) );
        assertHighest( of( Short.MAX_VALUE ) );
        assertHighest( of( Integer.MAX_VALUE ) );
        assertHighest( of( Long.MAX_VALUE ) );
        assertHighest( of( Float.POSITIVE_INFINITY ) );
        assertHighest( of( Double.POSITIVE_INFINITY ) );
        // GEOMETRY_ARRAY
        assertHighest( pointArray( new PointValue[]{PointValue.MAX_VALUE} ) );
        // ZONED_DATE_TIME_ARRAY
        assertHighest( dateTimeArray( new ZonedDateTime[]{DateTimeValue.MAX_VALUE.asObjectCopy()} ) );
        // LOCAL_DATE_TIME_ARRAY
        assertHighest( localDateTimeArray( new LocalDateTime[]{LocalDateTimeValue.MAX_VALUE.asObjectCopy()} ) );
        // DATE_ARRAY
        assertHighest( dateArray( new LocalDate[]{DateValue.MAX_VALUE.asObjectCopy()} ) );
        // ZONED_TIME_ARRAY
        assertHighest( timeArray( new OffsetTime[]{TimeValue.MAX_VALUE.asObjectCopy()} ) );
        // LOCAL_TIME_ARRAY
        assertHighest( localTimeArray( new LocalTime[]{LocalTimeValue.MAX_VALUE.asObjectCopy()} ) );
        // DURATION_ARRAY (DurationValue, TemporalAmount)
        assertHighest( durationArray( new DurationValue[]{DurationValue.duration( Duration.ofSeconds( Long.MAX_VALUE, 999_999_999 ) )} ) );
        assertHighest( durationArray( new DurationValue[]{DurationValue.duration( Period.of( Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE ) )} ) );
        assertHighest( durationArray( new TemporalAmount[]{Duration.ofSeconds( Long.MAX_VALUE, 999_999_999 )} ) );
        assertHighest( durationArray( new TemporalAmount[]{Period.of( Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE )} ) );
        // TEXT_ARRAY
        assertHighestStringArray();
        // BOOLEAN_ARRAY
        assertHighest( booleanArray( new boolean[]{true} ) );
        // NUMBER_ARRAY (byte[], short[], int[], long[], float[], double[])
        assertHighest( byteArray( new byte[]{Byte.MAX_VALUE} ) );
        assertHighest( shortArray( new short[]{Short.MAX_VALUE} ) );
        assertHighest( intArray( new int[]{Integer.MAX_VALUE} ) );
        assertHighest( longArray( new long[]{Long.MAX_VALUE} ) );
        assertHighest( floatArray( new float[]{Float.POSITIVE_INFINITY} ) );
        assertHighest( doubleArray( new double[]{Double.POSITIVE_INFINITY} ) );
    }

    @Test
    void shouldNeverOverwriteDereferencedTextValues()
    {
        // Given a value that we dereference
        Value srcValue = Values.utf8Value( "First string".getBytes( StandardCharsets.UTF_8 ) );
        GenericKeyState genericKeyState = newKeyState();
        genericKeyState.writeValue( srcValue, NEUTRAL );
        Value dereferencedValue = genericKeyState.asValue();
        assertEquals( srcValue, dereferencedValue );

        // and write to page
        PageCursor cursor = newPageCursor();
        int offset = cursor.getOffset();
        genericKeyState.put( cursor );
        int keySize = cursor.getOffset() - offset;
        cursor.setOffset( offset );

        // we should not overwrite the first dereferenced value when initializing from a new value
        genericKeyState.clear();
        Value srcValue2 = Values.utf8Value( "Secondstring".getBytes( StandardCharsets.UTF_8 ) ); // <- Same length as first string
        genericKeyState.writeValue( srcValue2, NEUTRAL );
        Value dereferencedValue2 = genericKeyState.asValue();
        assertEquals( srcValue2, dereferencedValue2 );
        assertEquals( srcValue, dereferencedValue );

        // and we should not overwrite the second value when we read back the first value from page
        genericKeyState.clear();
        genericKeyState.read( cursor, keySize );
        Value dereferencedValue3 = genericKeyState.asValue();
        assertEquals( srcValue, dereferencedValue3 );
        assertEquals( srcValue2, dereferencedValue2 );
        assertEquals( srcValue, dereferencedValue );
    }

    @Test
    void indexedCharShouldComeBackAsCharValue()
    {
        shouldReadBackToExactOriginalValue( random.randomValues().nextCharValue() );
    }

    @Test
    void indexedCharArrayShouldComeBackAsCharArrayValue()
    {
        shouldReadBackToExactOriginalValue( random.randomValues().nextCharArray() );
    }

    private void shouldReadBackToExactOriginalValue( Value srcValue )
    {
        // given
        GenericKeyState state = newKeyState();
        state.clear();
        state.writeValue( srcValue, NEUTRAL );
        Value retrievedValueAfterWrittenToState = state.asValue();
        assertEquals( srcValue, retrievedValueAfterWrittenToState );
        assertEquals( srcValue.getClass(), retrievedValueAfterWrittenToState.getClass() );

        // ... which is written to cursor
        PageCursor cursor = newPageCursor();
        int offset = cursor.getOffset();
        state.put( cursor );
        int keySize = cursor.getOffset() - offset;
        cursor.setOffset( offset );

        // when reading it back
        state.clear();
        state.read( cursor, keySize );

        // then it should also be retrieved as char value
        Value retrievedValueAfterReadFromCursor = state.asValue();
        assertEquals( srcValue, retrievedValueAfterReadFromCursor );
        assertEquals( srcValue.getClass(), retrievedValueAfterReadFromCursor.getClass() );
    }

    private void assertHighestStringArray()
    {
        for ( int i = 0; i < 1000; i++ )
        {
            assertHighest( random.randomValues().nextTextArray() );
        }
    }

    private void assertHighestString()
    {
        for ( int i = 0; i < 1000; i++ )
        {
            assertHighest( random.randomValues().nextTextValue() );
        }
    }

    private void assertHighest( Value value )
    {
        GenericKeyState highestOfAll = newKeyState();
        GenericKeyState highestInValueGroup = newKeyState();
        GenericKeyState other = newKeyState();
        highestOfAll.initValueAsHighest( ValueGroup.UNKNOWN );
        highestInValueGroup.initValueAsHighest( value.valueGroup() );
        other.writeValue( value, NEUTRAL );
        assertTrue( highestInValueGroup.compareValueTo( other ) > 0, "highestInValueGroup not higher than " + value );
        assertTrue( highestOfAll.compareValueTo( other ) > 0, "highestOfAll not higher than " + value );
        assertTrue( highestOfAll.compareValueTo( highestInValueGroup ) > 0 || highestOfAll.type == highestInValueGroup.type,
                "highestOfAll not higher than highestInValueGroup" );
    }

    private void assertLowest( Value value )
    {
        GenericKeyState lowestOfAll = newKeyState();
        GenericKeyState lowestInValueGroup = newKeyState();
        GenericKeyState other = newKeyState();
        lowestOfAll.initValueAsLowest( ValueGroup.UNKNOWN );
        lowestInValueGroup.initValueAsLowest( value.valueGroup() );
        other.writeValue( value, NEUTRAL );
        assertTrue( lowestInValueGroup.compareValueTo( other ) <= 0 );
        assertTrue( lowestOfAll.compareValueTo( other ) <= 0 );
        assertTrue( lowestOfAll.compareValueTo( lowestInValueGroup ) <= 0 );
    }

    private Value pickSmaller( Value value1, Value value2 )
    {
        return COMPARATOR.compare( value1, value2 ) < 0 ? value1 : value2;
    }

    private void assertValidMinimalSplitter( Value left, Value right )
    {
        GenericKeyState leftState = newKeyState();
        leftState.writeValue( left, NEUTRAL );
        GenericKeyState rightState = newKeyState();
        rightState.writeValue( right, NEUTRAL );

        GenericKeyState minimalSplitter = newKeyState();
        GenericKeyState.minimalSplitter( leftState, rightState, minimalSplitter );

        assertTrue( leftState.compareValueTo( minimalSplitter ) < 0,
                "left state not less than minimal splitter, leftState=" + leftState + ", rightState=" + rightState + ", minimalSplitter=" + minimalSplitter );
        assertTrue( rightState.compareValueTo( minimalSplitter ) >= 0,
                "right state not less than minimal splitter, leftState=" + leftState + ", rightState=" + rightState + ", minimalSplitter=" + minimalSplitter );
    }

    private void assertValidMinimalSplitterForEqualValues( Value value )
    {
        GenericKeyState leftState = newKeyState();
        leftState.writeValue( value, NEUTRAL );
        GenericKeyState rightState = newKeyState();
        rightState.writeValue( value, NEUTRAL );

        GenericKeyState minimalSplitter = newKeyState();
        GenericKeyState.minimalSplitter( leftState, rightState, minimalSplitter );

        assertEquals( 0, leftState.compareValueTo( minimalSplitter ),
                "left state not equal to minimal splitter, leftState=" + leftState + ", rightState=" + rightState + ", minimalSplitter=" + minimalSplitter );
        assertEquals( 0, rightState.compareValueTo( minimalSplitter ),
                "right state equal to minimal splitter, leftState=" + leftState + ", rightState=" + rightState + ", minimalSplitter=" + minimalSplitter );
    }

    private static Value nextValidValue( boolean includeIncomparable )
    {
        Value value;
        do
        {
            value = random.randomValues().nextValue();
        }
        while ( !includeIncomparable && isIncomparable( value ) );
        return value;
    }

    private static boolean isIncomparable( Value value )
    {
        return isGeometryValue( value ) || isGeometryArray( value );
    }

    private static ValueGenerator[] listValueGenerators( boolean includeIncomparable )
    {
        List<ValueGenerator> generators = new ArrayList<>( asList(
                // single
                () -> random.randomValues().nextDateTimeValue(),
                () -> random.randomValues().nextLocalDateTimeValue(),
                () -> random.randomValues().nextDateValue(),
                () -> random.randomValues().nextTimeValue(),
                () -> random.randomValues().nextLocalTimeValue(),
                () -> random.randomValues().nextPeriod(),
                () -> random.randomValues().nextDuration(),
                () -> random.randomValues().nextCharValue(),
                () -> random.randomValues().nextTextValue(),
                () -> random.randomValues().nextAlphaNumericTextValue(),
                () -> random.randomValues().nextBooleanValue(),
                () -> random.randomValues().nextNumberValue(),
                // array
                () -> random.randomValues().nextDateTimeArray(),
                () -> random.randomValues().nextLocalDateTimeArray(),
                () -> random.randomValues().nextDateArray(),
                () -> random.randomValues().nextTimeArray(),
                () -> random.randomValues().nextLocalTimeArray(),
                () -> random.randomValues().nextDurationArray(),
                () -> random.randomValues().nextDurationArray(),
                () -> random.randomValues().nextCharArray(),
                () -> random.randomValues().nextTextArray(),
                () -> random.randomValues().nextAlphaNumericTextArray(),
                () -> random.randomValues().nextBooleanArray(),
                () -> random.randomValues().nextByteArray(),
                () -> random.randomValues().nextShortArray(),
                () -> random.randomValues().nextIntArray(),
                () -> random.randomValues().nextLongArray(),
                () -> random.randomValues().nextFloatArray(),
                () -> random.randomValues().nextDoubleArray(),
                // and a random
                () -> nextValidValue( includeIncomparable )
        ) );

        if ( includeIncomparable )
        {
            generators.addAll( asList(
                    // single
                    () -> random.randomValues().nextPointValue(),
                    () -> random.randomValues().nextGeographicPoint(),
                    () -> random.randomValues().nextGeographic3DPoint(),
                    () -> random.randomValues().nextCartesianPoint(),
                    () -> random.randomValues().nextCartesian3DPoint(),
                    // array
                    () -> random.randomValues().nextGeographicPointArray(),
                    () -> random.randomValues().nextGeographic3DPoint(),
                    () -> random.randomValues().nextCartesianPointArray(),
                    () -> random.randomValues().nextCartesian3DPointArray()
            ) );
        }

        return generators.toArray( new ValueGenerator[0] );
    }

    private static Stream<ValueGenerator> validValueGenerators()
    {
        return Stream.of( listValueGenerators( true ) );
    }

    private static Stream<ValueGenerator> validComparableValueGenerators()
    {
        return Stream.of( listValueGenerators( false ) );
    }

    private GenericKeyState genericKeyStateWithSomePreviousState( ValueGenerator valueGenerator )
    {
        GenericKeyState to = newKeyState();
        if ( random.nextBoolean() )
        {
            // Previous value
            NativeIndexKey.Inclusion inclusion = random.among( NativeIndexKey.Inclusion.values() );
            Value value = valueGenerator.next();
            to.writeValue( value, inclusion );
        }
        // No previous state
        return to;
    }

    private PageCursor newPageCursor()
    {
        return ByteArrayPageCursor.wrap( PageCache.PAGE_SIZE );
    }

    private GenericKeyState newKeyState()
    {
        return new GenericKeyState( noSpecificIndexSettings );
    }

    @FunctionalInterface
    private interface ValueGenerator
    {
        Value next();
    }
}
