/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.mapper.vectors;

import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.FloatDocValuesField;
import org.apache.lucene.document.KnnByteVectorField;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.ByteVectorValues;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.KnnVectorValues;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.VectorEncoding;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FieldExistsQuery;
import org.apache.lucene.search.KnnByteVectorQuery;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.PatienceKnnVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.search.knn.KnnSearchStrategy;
import org.apache.lucene.util.BitUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.VectorUtil;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.util.FeatureFlag;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.features.NodeFeature;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.IndexVersions;
import org.elasticsearch.index.codec.vectors.ES813FlatVectorFormat;
import org.elasticsearch.index.codec.vectors.ES813Int8FlatVectorFormat;
import org.elasticsearch.index.codec.vectors.ES814HnswScalarQuantizedVectorsFormat;
import org.elasticsearch.index.codec.vectors.ES815BitFlatVectorFormat;
import org.elasticsearch.index.codec.vectors.ES815HnswBitVectorsFormat;
import org.elasticsearch.index.codec.vectors.IVFVectorsFormat;
import org.elasticsearch.index.codec.vectors.es818.ES818BinaryQuantizedVectorsFormat;
import org.elasticsearch.index.codec.vectors.es818.ES818HnswBinaryQuantizedVectorsFormat;
import org.elasticsearch.index.fielddata.FieldDataContext;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.mapper.ArraySourceValueFetcher;
import org.elasticsearch.index.mapper.BlockDocValuesReader;
import org.elasticsearch.index.mapper.BlockLoader;
import org.elasticsearch.index.mapper.BlockSourceReader;
import org.elasticsearch.index.mapper.DocumentParserContext;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.index.mapper.MapperBuilderContext;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.MappingParser;
import org.elasticsearch.index.mapper.NumberFieldMapper;
import org.elasticsearch.index.mapper.SimpleMappedFieldType;
import org.elasticsearch.index.mapper.SourceLoader;
import org.elasticsearch.index.mapper.SourceValueFetcher;
import org.elasticsearch.index.mapper.TextSearchInfo;
import org.elasticsearch.index.mapper.ValueFetcher;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.aggregations.support.CoreValuesSourceType;
import org.elasticsearch.search.lookup.Source;
import org.elasticsearch.search.vectors.DenseVectorQuery;
import org.elasticsearch.search.vectors.DiversifyingChildrenIVFKnnFloatVectorQuery;
import org.elasticsearch.search.vectors.DiversifyingParentBlockQuery;
import org.elasticsearch.search.vectors.ESDiversifyingChildrenByteKnnVectorQuery;
import org.elasticsearch.search.vectors.ESDiversifyingChildrenFloatKnnVectorQuery;
import org.elasticsearch.search.vectors.ESKnnByteVectorQuery;
import org.elasticsearch.search.vectors.ESKnnFloatVectorQuery;
import org.elasticsearch.search.vectors.IVFKnnFloatVectorQuery;
import org.elasticsearch.search.vectors.RescoreKnnVectorQuery;
import org.elasticsearch.search.vectors.VectorData;
import org.elasticsearch.search.vectors.VectorSimilarityQuery;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParser.Token;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.elasticsearch.cluster.metadata.IndexMetadata.SETTING_INDEX_VERSION_CREATED;
import static org.elasticsearch.common.Strings.format;
import static org.elasticsearch.common.xcontent.XContentParserUtils.ensureExpectedToken;
import static org.elasticsearch.index.IndexSettings.INDEX_MAPPING_SOURCE_SYNTHETIC_VECTORS_SETTING;
import static org.elasticsearch.index.codec.vectors.IVFVectorsFormat.MAX_VECTORS_PER_CLUSTER;
import static org.elasticsearch.index.codec.vectors.IVFVectorsFormat.MIN_VECTORS_PER_CLUSTER;

/**
 * A {@link FieldMapper} for indexing a dense vector of floats.
 */
public class DenseVectorFieldMapper extends FieldMapper {
    public static final String COSINE_MAGNITUDE_FIELD_SUFFIX = "._magnitude";
    private static final float EPS = 1e-3f;
    public static final int BBQ_MIN_DIMS = 64;

    private static final boolean DEFAULT_HNSW_EARLY_TERMINATION = false;
    public static final FeatureFlag IVF_FORMAT = new FeatureFlag("ivf_format");

    public static boolean isNotUnitVector(float magnitude) {
        return Math.abs(magnitude - 1.0f) > EPS;
    }

    /**
     * The heuristic to utilize when executing a filtered search against vectors indexed in an HNSW graph.
     */
    public enum FilterHeuristic {
        /**
         * This heuristic searches the entire graph, doing vector comparisons in all immediate neighbors
         * but only collects vectors that match the filtering criteria.
         */
        FANOUT {
            static final KnnSearchStrategy FANOUT_STRATEGY = new KnnSearchStrategy.Hnsw(0);

            @Override
            public KnnSearchStrategy getKnnSearchStrategy() {
                return FANOUT_STRATEGY;
            }
        },
        /**
         * This heuristic will only compare vectors that match the filtering criteria.
         */
        ACORN {
            static final KnnSearchStrategy ACORN_STRATEGY = new KnnSearchStrategy.Hnsw(60);

            @Override
            public KnnSearchStrategy getKnnSearchStrategy() {
                return ACORN_STRATEGY;
            }
        };

        public abstract KnnSearchStrategy getKnnSearchStrategy();
    }

    public static final Setting<FilterHeuristic> HNSW_FILTER_HEURISTIC = Setting.enumSetting(FilterHeuristic.class, s -> {
        IndexVersion version = SETTING_INDEX_VERSION_CREATED.get(s);
        if (version.onOrAfter(IndexVersions.DEFAULT_TO_ACORN_HNSW_FILTER_HEURISTIC)) {
            return FilterHeuristic.ACORN.toString();
        }
        return FilterHeuristic.FANOUT.toString();
    },
        "index.dense_vector.hnsw_filter_heuristic",
        fh -> {},
        Setting.Property.IndexScope,
        Setting.Property.ServerlessPublic,
        Setting.Property.Dynamic
    );

    public static final Setting<Boolean> HNSW_EARLY_TERMINATION = Setting.boolSetting(
        "index.dense_vector.hnsw_enable_early_termination",
        DEFAULT_HNSW_EARLY_TERMINATION,
        Setting.Property.IndexScope,
        Setting.Property.ServerlessPublic,
        Setting.Property.Dynamic
    );

    private static boolean hasRescoreIndexVersion(IndexVersion version) {
        return version.onOrAfter(IndexVersions.ADD_RESCORE_PARAMS_TO_QUANTIZED_VECTORS)
            || version.between(IndexVersions.ADD_RESCORE_PARAMS_TO_QUANTIZED_VECTORS_BACKPORT_8_X, IndexVersions.UPGRADE_TO_LUCENE_10_0_0);
    }

    private static boolean allowsZeroRescore(IndexVersion version) {
        return version.onOrAfter(IndexVersions.RESCORE_PARAMS_ALLOW_ZERO_TO_QUANTIZED_VECTORS)
            || version.between(
                IndexVersions.RESCORE_PARAMS_ALLOW_ZERO_TO_QUANTIZED_VECTORS_BACKPORT_8_X,
                IndexVersions.UPGRADE_TO_LUCENE_10_0_0
            );
    }

    private static boolean defaultOversampleForBBQ(IndexVersion version) {
        return version.onOrAfter(IndexVersions.DEFAULT_OVERSAMPLE_VALUE_FOR_BBQ)
            || version.between(IndexVersions.DEFAULT_OVERSAMPLE_VALUE_FOR_BBQ_BACKPORT_8_X, IndexVersions.UPGRADE_TO_LUCENE_10_0_0);
    }

    public static final IndexVersion MAGNITUDE_STORED_INDEX_VERSION = IndexVersions.V_7_5_0;
    public static final IndexVersion INDEXED_BY_DEFAULT_INDEX_VERSION = IndexVersions.FIRST_DETACHED_INDEX_VERSION;
    public static final IndexVersion NORMALIZE_COSINE = IndexVersions.NORMALIZED_VECTOR_COSINE;
    public static final IndexVersion DEFAULT_TO_INT8 = IndexVersions.DEFAULT_DENSE_VECTOR_TO_INT8_HNSW;
    public static final IndexVersion DEFAULT_TO_BBQ = IndexVersions.DEFAULT_DENSE_VECTOR_TO_BBQ_HNSW;
    public static final IndexVersion LITTLE_ENDIAN_FLOAT_STORED_INDEX_VERSION = IndexVersions.V_8_9_0;

    public static final NodeFeature RESCORE_VECTOR_QUANTIZED_VECTOR_MAPPING = new NodeFeature("mapper.dense_vector.rescore_vector");
    public static final NodeFeature RESCORE_ZERO_VECTOR_QUANTIZED_VECTOR_MAPPING = new NodeFeature(
        "mapper.dense_vector.rescore_zero_vector"
    );
    public static final NodeFeature USE_DEFAULT_OVERSAMPLE_VALUE_FOR_BBQ = new NodeFeature(
        "mapper.dense_vector.default_oversample_value_for_bbq"
    );

    public static final String CONTENT_TYPE = "dense_vector";
    public static final short MAX_DIMS_COUNT = 4096; // maximum allowed number of dimensions
    public static final int MAX_DIMS_COUNT_BIT = 4096 * Byte.SIZE; // maximum allowed number of dimensions

    public static final short MIN_DIMS_FOR_DYNAMIC_FLOAT_MAPPING = 128; // minimum number of dims for floats to be dynamically mapped to
    // vector
    public static final int MAGNITUDE_BYTES = 4;
    public static final int OVERSAMPLE_LIMIT = 10_000; // Max oversample allowed
    public static final float DEFAULT_OVERSAMPLE = 3.0F; // Default oversample value
    public static final int BBQ_DIMS_DEFAULT_THRESHOLD = 384; // Lower bound for dimensions for using bbq_hnsw as default index options

    private static DenseVectorFieldMapper toType(FieldMapper in) {
        return (DenseVectorFieldMapper) in;
    }

    public static class Builder extends FieldMapper.Builder {

        private final Parameter<ElementType> elementType = new Parameter<>("element_type", false, () -> ElementType.FLOAT, (n, c, o) -> {
            ElementType elementType = namesToElementType.get((String) o);
            if (elementType == null) {
                throw new MapperParsingException("invalid element_type [" + o + "]; available types are " + namesToElementType.keySet());
            }
            return elementType;
        }, m -> toType(m).fieldType().elementType, XContentBuilder::field, Objects::toString);
        private final Parameter<Integer> dims;
        private final Parameter<VectorSimilarity> similarity;

        private final Parameter<DenseVectorIndexOptions> indexOptions;

        private final Parameter<Boolean> indexed;
        private final Parameter<Map<String, String>> meta = Parameter.metaParam();

        final IndexVersion indexVersionCreated;
        final boolean isSyntheticVector;

        public Builder(String name, IndexVersion indexVersionCreated, boolean isSyntheticVector) {
            super(name);
            this.indexVersionCreated = indexVersionCreated;
            // This is defined as updatable because it can be updated once, from [null] to a valid dim size,
            // by a dynamic mapping update. Once it has been set, however, the value cannot be changed.
            this.dims = new Parameter<>("dims", true, () -> null, (n, c, o) -> {
                if (o instanceof Integer == false) {
                    throw new MapperParsingException("Property [dims] on field [" + n + "] must be an integer but got [" + o + "]");
                }

                return XContentMapValues.nodeIntegerValue(o);
            }, m -> toType(m).fieldType().dims, XContentBuilder::field, Objects::toString).setSerializerCheck((id, ic, v) -> v != null)
                .setMergeValidator((previous, current, c) -> previous == null || Objects.equals(previous, current))
                .addValidator(dims -> {
                    if (dims == null) {
                        return;
                    }
                    int maxDims = elementType.getValue() == ElementType.BIT ? MAX_DIMS_COUNT_BIT : MAX_DIMS_COUNT;
                    int minDims = elementType.getValue() == ElementType.BIT ? Byte.SIZE : 1;
                    if (dims < minDims || dims > maxDims) {
                        throw new MapperParsingException(
                            "The number of dimensions should be in the range [" + minDims + ", " + maxDims + "] but was [" + dims + "]"
                        );
                    }
                    if (elementType.getValue() == ElementType.BIT) {
                        if (dims % Byte.SIZE != 0) {
                            throw new MapperParsingException(
                                "The number of dimensions for should be a multiple of 8 but was [" + dims + "]"
                            );
                        }
                    }
                });
            this.isSyntheticVector = isSyntheticVector;
            final boolean indexedByDefault = indexVersionCreated.onOrAfter(INDEXED_BY_DEFAULT_INDEX_VERSION);
            final boolean defaultInt8Hnsw = indexVersionCreated.onOrAfter(IndexVersions.DEFAULT_DENSE_VECTOR_TO_INT8_HNSW);
            final boolean defaultBBQ8Hnsw = indexVersionCreated.onOrAfter(IndexVersions.DEFAULT_DENSE_VECTOR_TO_BBQ_HNSW);
            this.indexed = Parameter.indexParam(m -> toType(m).fieldType().indexed, indexedByDefault);
            if (indexedByDefault) {
                // Only serialize on newer index versions to prevent breaking existing indices when upgrading
                this.indexed.alwaysSerialize();
            }
            this.similarity = Parameter.enumParam(
                "similarity",
                false,
                m -> toType(m).fieldType().similarity,
                (Supplier<VectorSimilarity>) () -> {
                    if (indexedByDefault && indexed.getValue()) {
                        return elementType.getValue() == ElementType.BIT ? VectorSimilarity.L2_NORM : VectorSimilarity.COSINE;
                    }
                    return null;
                },
                VectorSimilarity.class
            ).acceptsNull().setSerializerCheck((id, ic, v) -> v != null).addValidator(vectorSim -> {
                if (vectorSim == null) {
                    return;
                }
                if (elementType.getValue() == ElementType.BIT && vectorSim != VectorSimilarity.L2_NORM) {
                    throw new IllegalArgumentException(
                        "The [" + VectorSimilarity.L2_NORM + "] similarity is the only supported similarity for bit vectors"
                    );
                }
            });
            this.indexOptions = new Parameter<>(
                "index_options",
                true,
                () -> defaultIndexOptions(defaultInt8Hnsw, defaultBBQ8Hnsw),
                (n, c, o) -> o == null ? null : parseIndexOptions(n, o, indexVersionCreated),
                m -> toType(m).indexOptions,
                (b, n, v) -> {
                    if (v != null) {
                        b.field(n, v);
                    }
                },
                Objects::toString
            ).setSerializerCheck((id, ic, v) -> v != null).addValidator(v -> {
                if (v != null && dims.isConfigured() && dims.get() != null) {
                    v.validateDimension(dims.get());
                }
                if (v != null) {
                    v.validateElementType(elementType.getValue());
                }
            })
                .acceptsNull()
                .setMergeValidator(
                    (previous, current, c) -> previous == null
                        || current == null
                        || Objects.equals(previous, current)
                        || previous.updatableTo(current)
                );
            if (defaultInt8Hnsw || defaultBBQ8Hnsw) {
                if (defaultBBQ8Hnsw == false || (dims != null && dims.isConfigured())) {
                    this.indexOptions.alwaysSerialize();
                }
            }
            this.indexed.addValidator(v -> {
                if (v) {
                    if (similarity.getValue() == null) {
                        throw new IllegalArgumentException("Field [index] requires field [similarity] to be configured and not null");
                    }
                } else {
                    if (similarity.isConfigured() && similarity.getValue() != null) {
                        throw new IllegalArgumentException(
                            "Field [similarity] can only be specified for a field of type [dense_vector] when it is indexed"
                        );
                    }
                    if (indexOptions.isConfigured() && indexOptions.getValue() != null) {
                        throw new IllegalArgumentException(
                            "Field [index_options] can only be specified for a field of type [dense_vector] when it is indexed"
                        );
                    }
                }
            });
        }

        private DenseVectorIndexOptions defaultIndexOptions(boolean defaultInt8Hnsw, boolean defaultBBQHnsw) {
            if (elementType.getValue() != ElementType.FLOAT || indexed.getValue() == false) {
                return null;
            }

            boolean dimIsConfigured = dims != null && dims.isConfigured();
            if (defaultBBQHnsw && dimIsConfigured == false) {
                // Delay selecting the default index options until dimensions are configured.
                // This applies only to indices that are eligible to use BBQ as the default,
                // since prior to this change, the default was selected eagerly.
                return null;
            }

            if (defaultBBQHnsw && dimIsConfigured && dims.getValue() >= BBQ_DIMS_DEFAULT_THRESHOLD) {
                return new BBQHnswIndexOptions(
                    Lucene99HnswVectorsFormat.DEFAULT_MAX_CONN,
                    Lucene99HnswVectorsFormat.DEFAULT_BEAM_WIDTH,
                    new RescoreVector(DEFAULT_OVERSAMPLE)
                );
            } else if (defaultInt8Hnsw) {
                return new Int8HnswIndexOptions(
                    Lucene99HnswVectorsFormat.DEFAULT_MAX_CONN,
                    Lucene99HnswVectorsFormat.DEFAULT_BEAM_WIDTH,
                    null,
                    null
                );
            }
            return null;
        }

        @Override
        protected Parameter<?>[] getParameters() {
            return new Parameter<?>[] { elementType, dims, indexed, similarity, indexOptions, meta };
        }

        public Builder similarity(VectorSimilarity vectorSimilarity) {
            similarity.setValue(vectorSimilarity);
            return this;
        }

        public Builder dimensions(int dimensions) {
            this.dims.setValue(dimensions);
            return this;
        }

        public Builder elementType(ElementType elementType) {
            this.elementType.setValue(elementType);
            return this;
        }

        public Builder indexOptions(DenseVectorIndexOptions indexOptions) {
            this.indexOptions.setValue(indexOptions);
            return this;
        }

        @Override
        public DenseVectorFieldMapper build(MapperBuilderContext context) {
            // Validate again here because the dimensions or element type could have been set programmatically,
            // which affects index option validity
            validate();
            boolean isSyntheticVectorFinal = (context.isSourceSynthetic() == false) && indexed.getValue() && isSyntheticVector;
            return new DenseVectorFieldMapper(
                leafName(),
                new DenseVectorFieldType(
                    context.buildFullName(leafName()),
                    indexVersionCreated,
                    elementType.getValue(),
                    dims.getValue(),
                    indexed.getValue(),
                    similarity.getValue(),
                    indexOptions.getValue(),
                    meta.getValue(),
                    context.isSourceSynthetic()
                ),
                builderParams(this, context),
                indexOptions.getValue(),
                indexVersionCreated,
                isSyntheticVectorFinal
            );
        }
    }

    public enum ElementType {

        BYTE {

            @Override
            public String toString() {
                return "byte";
            }

            @Override
            public void writeValue(ByteBuffer byteBuffer, float value) {
                byteBuffer.put((byte) value);
            }

            @Override
            public void readAndWriteValue(ByteBuffer byteBuffer, XContentBuilder b) throws IOException {
                b.value(byteBuffer.get());
            }

            private KnnByteVectorField createKnnVectorField(String name, byte[] vector, VectorSimilarityFunction function) {
                if (vector == null) {
                    throw new IllegalArgumentException("vector value must not be null");
                }
                FieldType denseVectorFieldType = new FieldType();
                denseVectorFieldType.setVectorAttributes(vector.length, VectorEncoding.BYTE, function);
                denseVectorFieldType.freeze();
                return new KnnByteVectorField(name, vector, denseVectorFieldType);
            }

            @Override
            IndexFieldData.Builder fielddataBuilder(DenseVectorFieldType denseVectorFieldType, FieldDataContext fieldDataContext) {
                return new VectorIndexFieldData.Builder(
                    denseVectorFieldType.name(),
                    CoreValuesSourceType.KEYWORD,
                    denseVectorFieldType.indexVersionCreated,
                    this,
                    denseVectorFieldType.dims,
                    denseVectorFieldType.indexed,
                    r -> r
                );
            }

            @Override
            StringBuilder checkVectorErrors(float[] vector) {
                StringBuilder errors = checkNanAndInfinite(vector);
                if (errors != null) {
                    return errors;
                }

                for (int index = 0; index < vector.length; ++index) {
                    float value = vector[index];

                    if (value % 1.0f != 0.0f) {
                        errors = new StringBuilder(
                            "element_type ["
                                + this
                                + "] vectors only support non-decimal values but found decimal value ["
                                + value
                                + "] at dim ["
                                + index
                                + "];"
                        );
                        break;
                    }

                    if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
                        errors = new StringBuilder(
                            "element_type ["
                                + this
                                + "] vectors only support integers between ["
                                + Byte.MIN_VALUE
                                + ", "
                                + Byte.MAX_VALUE
                                + "] but found ["
                                + value
                                + "] at dim ["
                                + index
                                + "];"
                        );
                        break;
                    }
                }

                return errors;
            }

            @Override
            void checkVectorMagnitude(
                VectorSimilarity similarity,
                Function<StringBuilder, StringBuilder> appender,
                float squaredMagnitude
            ) {
                StringBuilder errorBuilder = null;

                if (similarity == VectorSimilarity.COSINE && Math.sqrt(squaredMagnitude) == 0.0f) {
                    errorBuilder = new StringBuilder(
                        "The [" + VectorSimilarity.COSINE + "] similarity does not support vectors with zero magnitude."
                    );
                }

                if (errorBuilder != null) {
                    throw new IllegalArgumentException(appender.apply(errorBuilder).toString());
                }
            }

            @Override
            public double computeSquaredMagnitude(VectorData vectorData) {
                return VectorUtil.dotProduct(vectorData.asByteVector(), vectorData.asByteVector());
            }

            private VectorData parseVectorArray(
                DocumentParserContext context,
                int dims,
                IntBooleanConsumer dimChecker,
                VectorSimilarity similarity
            ) throws IOException {
                int index = 0;
                byte[] vector = new byte[dims];
                float squaredMagnitude = 0;
                for (XContentParser.Token token = context.parser().nextToken(); token != Token.END_ARRAY; token = context.parser()
                    .nextToken()) {
                    dimChecker.accept(index, false);
                    ensureExpectedToken(Token.VALUE_NUMBER, token, context.parser());
                    final int value;
                    if (context.parser().numberType() != XContentParser.NumberType.INT) {
                        float floatValue = context.parser().floatValue(true);
                        if (floatValue % 1.0f != 0.0f) {
                            throw new IllegalArgumentException(
                                "element_type ["
                                    + this
                                    + "] vectors only support non-decimal values but found decimal value ["
                                    + floatValue
                                    + "] at dim ["
                                    + index
                                    + "];"
                            );
                        }
                        value = (int) floatValue;
                    } else {
                        value = context.parser().intValue(true);
                    }
                    if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
                        throw new IllegalArgumentException(
                            "element_type ["
                                + this
                                + "] vectors only support integers between ["
                                + Byte.MIN_VALUE
                                + ", "
                                + Byte.MAX_VALUE
                                + "] but found ["
                                + value
                                + "] at dim ["
                                + index
                                + "];"
                        );
                    }
                    vector[index++] = (byte) value;
                    squaredMagnitude += value * value;
                }
                dimChecker.accept(index, true);
                checkVectorMagnitude(similarity, errorByteElementsAppender(vector), squaredMagnitude);
                return VectorData.fromBytes(vector);
            }

            private VectorData parseHexEncodedVector(
                DocumentParserContext context,
                IntBooleanConsumer dimChecker,
                VectorSimilarity similarity
            ) throws IOException {
                byte[] decodedVector = HexFormat.of().parseHex(context.parser().text());
                dimChecker.accept(decodedVector.length, true);
                VectorData vectorData = VectorData.fromBytes(decodedVector);
                double squaredMagnitude = computeSquaredMagnitude(vectorData);
                checkVectorMagnitude(similarity, errorByteElementsAppender(decodedVector), (float) squaredMagnitude);
                return vectorData;
            }

            @Override
            public VectorData parseKnnVector(
                DocumentParserContext context,
                int dims,
                IntBooleanConsumer dimChecker,
                VectorSimilarity similarity
            ) throws IOException {
                XContentParser.Token token = context.parser().currentToken();
                return switch (token) {
                    case START_ARRAY -> parseVectorArray(context, dims, dimChecker, similarity);
                    case VALUE_STRING -> parseHexEncodedVector(context, dimChecker, similarity);
                    default -> throw new ParsingException(
                        context.parser().getTokenLocation(),
                        format("Unsupported type [%s] for provided value [%s]", token, context.parser().text())
                    );
                };
            }

            @Override
            public void parseKnnVectorAndIndex(DocumentParserContext context, DenseVectorFieldMapper fieldMapper) throws IOException {
                VectorData vectorData = parseKnnVector(context, fieldMapper.fieldType().dims, (i, end) -> {
                    if (end) {
                        fieldMapper.checkDimensionMatches(i, context);
                    } else {
                        fieldMapper.checkDimensionExceeded(i, context);
                    }
                }, fieldMapper.fieldType().similarity);
                Field field = createKnnVectorField(
                    fieldMapper.fieldType().name(),
                    vectorData.asByteVector(),
                    fieldMapper.fieldType().similarity.vectorSimilarityFunction(fieldMapper.indexCreatedVersion, this)
                );
                context.doc().addWithKey(fieldMapper.fieldType().name(), field);
            }

            @Override
            public int getNumBytes(int dimensions) {
                return dimensions;
            }

            @Override
            public ByteBuffer createByteBuffer(IndexVersion indexVersion, int numBytes) {
                return ByteBuffer.wrap(new byte[numBytes]);
            }

            @Override
            public int parseDimensionCount(DocumentParserContext context) throws IOException {
                XContentParser.Token currentToken = context.parser().currentToken();
                return switch (currentToken) {
                    case START_ARRAY -> {
                        int index = 0;
                        for (Token token = context.parser().nextToken(); token != Token.END_ARRAY; token = context.parser().nextToken()) {
                            index++;
                        }
                        yield index;
                    }
                    case VALUE_STRING -> {
                        byte[] decodedVector = HexFormat.of().parseHex(context.parser().text());
                        yield decodedVector.length;
                    }
                    default -> throw new ParsingException(
                        context.parser().getTokenLocation(),
                        format("Unsupported type [%s] for provided value [%s]", currentToken, context.parser().text())
                    );
                };
            }
        },

        FLOAT {

            @Override
            public String toString() {
                return "float";
            }

            @Override
            public void writeValue(ByteBuffer byteBuffer, float value) {
                byteBuffer.putFloat(value);
            }

            @Override
            public void readAndWriteValue(ByteBuffer byteBuffer, XContentBuilder b) throws IOException {
                b.value(byteBuffer.getFloat());
            }

            private KnnFloatVectorField createKnnVectorField(String name, float[] vector, VectorSimilarityFunction function) {
                if (vector == null) {
                    throw new IllegalArgumentException("vector value must not be null");
                }
                FieldType denseVectorFieldType = new FieldType();
                denseVectorFieldType.setVectorAttributes(vector.length, VectorEncoding.FLOAT32, function);
                denseVectorFieldType.freeze();
                return new KnnFloatVectorField(name, vector, denseVectorFieldType);
            }

            @Override
            IndexFieldData.Builder fielddataBuilder(DenseVectorFieldType denseVectorFieldType, FieldDataContext fieldDataContext) {
                return new VectorIndexFieldData.Builder(
                    denseVectorFieldType.name(),
                    CoreValuesSourceType.KEYWORD,
                    denseVectorFieldType.indexVersionCreated,
                    this,
                    denseVectorFieldType.dims,
                    denseVectorFieldType.indexed,
                    denseVectorFieldType.indexVersionCreated.onOrAfter(NORMALIZE_COSINE)
                        && denseVectorFieldType.indexed
                        && denseVectorFieldType.similarity.equals(VectorSimilarity.COSINE) ? r -> new FilterLeafReader(r) {
                            @Override
                            public CacheHelper getCoreCacheHelper() {
                                return r.getCoreCacheHelper();
                            }

                            @Override
                            public CacheHelper getReaderCacheHelper() {
                                return r.getReaderCacheHelper();
                            }

                            @Override
                            public FloatVectorValues getFloatVectorValues(String fieldName) throws IOException {
                                FloatVectorValues values = in.getFloatVectorValues(fieldName);
                                if (values == null) {
                                    return null;
                                }
                                return new DenormalizedCosineFloatVectorValues(
                                    values,
                                    in.getNumericDocValues(fieldName + COSINE_MAGNITUDE_FIELD_SUFFIX)
                                );
                            }
                        } : r -> r
                );
            }

            @Override
            StringBuilder checkVectorErrors(float[] vector) {
                return checkNanAndInfinite(vector);
            }

            @Override
            void checkVectorMagnitude(
                VectorSimilarity similarity,
                Function<StringBuilder, StringBuilder> appender,
                float squaredMagnitude
            ) {
                StringBuilder errorBuilder = null;

                if (Float.isNaN(squaredMagnitude) || Float.isInfinite(squaredMagnitude)) {
                    errorBuilder = new StringBuilder(
                        "NaN or Infinite magnitude detected, this usually means the vector values are too extreme to fit within a float."
                    );
                }
                if (errorBuilder != null) {
                    throw new IllegalArgumentException(appender.apply(errorBuilder).toString());
                }

                if (similarity == VectorSimilarity.DOT_PRODUCT && isNotUnitVector(squaredMagnitude)) {
                    errorBuilder = new StringBuilder(
                        "The [" + VectorSimilarity.DOT_PRODUCT + "] similarity can only be used with unit-length vectors."
                    );
                } else if (similarity == VectorSimilarity.COSINE && Math.sqrt(squaredMagnitude) == 0.0f) {
                    errorBuilder = new StringBuilder(
                        "The [" + VectorSimilarity.COSINE + "] similarity does not support vectors with zero magnitude."
                    );
                }

                if (errorBuilder != null) {
                    throw new IllegalArgumentException(appender.apply(errorBuilder).toString());
                }
            }

            @Override
            public double computeSquaredMagnitude(VectorData vectorData) {
                return VectorUtil.dotProduct(vectorData.asFloatVector(), vectorData.asFloatVector());
            }

            @Override
            public void parseKnnVectorAndIndex(DocumentParserContext context, DenseVectorFieldMapper fieldMapper) throws IOException {
                int index = 0;
                float[] vector = new float[fieldMapper.fieldType().dims];
                float squaredMagnitude = 0;
                for (Token token = context.parser().nextToken(); token != Token.END_ARRAY; token = context.parser().nextToken()) {
                    fieldMapper.checkDimensionExceeded(index, context);
                    ensureExpectedToken(Token.VALUE_NUMBER, token, context.parser());

                    float value = context.parser().floatValue(true);
                    vector[index++] = value;
                    squaredMagnitude += value * value;
                }
                fieldMapper.checkDimensionMatches(index, context);
                checkVectorBounds(vector);
                checkVectorMagnitude(fieldMapper.fieldType().similarity, errorFloatElementsAppender(vector), squaredMagnitude);
                if (fieldMapper.indexCreatedVersion.onOrAfter(NORMALIZE_COSINE)
                    && fieldMapper.fieldType().similarity.equals(VectorSimilarity.COSINE)
                    && isNotUnitVector(squaredMagnitude)) {
                    float length = (float) Math.sqrt(squaredMagnitude);
                    for (int i = 0; i < vector.length; i++) {
                        vector[i] /= length;
                    }
                    final String fieldName = fieldMapper.fieldType().name() + COSINE_MAGNITUDE_FIELD_SUFFIX;
                    Field magnitudeField = new FloatDocValuesField(fieldName, length);
                    context.doc().addWithKey(fieldName, magnitudeField);
                }
                Field field = createKnnVectorField(
                    fieldMapper.fieldType().name(),
                    vector,
                    fieldMapper.fieldType().similarity.vectorSimilarityFunction(fieldMapper.indexCreatedVersion, this)
                );
                context.doc().addWithKey(fieldMapper.fieldType().name(), field);
            }

            @Override
            public VectorData parseKnnVector(
                DocumentParserContext context,
                int dims,
                IntBooleanConsumer dimChecker,
                VectorSimilarity similarity
            ) throws IOException {
                int index = 0;
                float squaredMagnitude = 0;
                float[] vector = new float[dims];
                for (Token token = context.parser().nextToken(); token != Token.END_ARRAY; token = context.parser().nextToken()) {
                    dimChecker.accept(index, false);
                    ensureExpectedToken(Token.VALUE_NUMBER, token, context.parser());
                    float value = context.parser().floatValue(true);
                    vector[index] = value;
                    squaredMagnitude += value * value;
                    index++;
                }
                dimChecker.accept(index, true);
                checkVectorBounds(vector);
                checkVectorMagnitude(similarity, errorFloatElementsAppender(vector), squaredMagnitude);
                return VectorData.fromFloats(vector);
            }

            @Override
            public int getNumBytes(int dimensions) {
                return dimensions * Float.BYTES;
            }

            @Override
            public ByteBuffer createByteBuffer(IndexVersion indexVersion, int numBytes) {
                return indexVersion.onOrAfter(LITTLE_ENDIAN_FLOAT_STORED_INDEX_VERSION)
                    ? ByteBuffer.wrap(new byte[numBytes]).order(ByteOrder.LITTLE_ENDIAN)
                    : ByteBuffer.wrap(new byte[numBytes]);
            }
        },

        BIT {

            @Override
            public String toString() {
                return "bit";
            }

            @Override
            public void writeValue(ByteBuffer byteBuffer, float value) {
                byteBuffer.put((byte) value);
            }

            @Override
            public void readAndWriteValue(ByteBuffer byteBuffer, XContentBuilder b) throws IOException {
                b.value(byteBuffer.get());
            }

            private KnnByteVectorField createKnnVectorField(String name, byte[] vector, VectorSimilarityFunction function) {
                if (vector == null) {
                    throw new IllegalArgumentException("vector value must not be null");
                }
                FieldType denseVectorFieldType = new FieldType();
                denseVectorFieldType.setVectorAttributes(vector.length, VectorEncoding.BYTE, function);
                denseVectorFieldType.freeze();
                return new KnnByteVectorField(name, vector, denseVectorFieldType);
            }

            @Override
            IndexFieldData.Builder fielddataBuilder(DenseVectorFieldType denseVectorFieldType, FieldDataContext fieldDataContext) {
                return new VectorIndexFieldData.Builder(
                    denseVectorFieldType.name(),
                    CoreValuesSourceType.KEYWORD,
                    denseVectorFieldType.indexVersionCreated,
                    this,
                    denseVectorFieldType.dims,
                    denseVectorFieldType.indexed,
                    r -> r
                );
            }

            @Override
            StringBuilder checkVectorErrors(float[] vector) {
                StringBuilder errors = checkNanAndInfinite(vector);
                if (errors != null) {
                    return errors;
                }

                for (int index = 0; index < vector.length; ++index) {
                    float value = vector[index];

                    if (value % 1.0f != 0.0f) {
                        errors = new StringBuilder(
                            "element_type ["
                                + this
                                + "] vectors only support non-decimal values but found decimal value ["
                                + value
                                + "] at dim ["
                                + index
                                + "];"
                        );
                        break;
                    }

                    if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
                        errors = new StringBuilder(
                            "element_type ["
                                + this
                                + "] vectors only support integers between ["
                                + Byte.MIN_VALUE
                                + ", "
                                + Byte.MAX_VALUE
                                + "] but found ["
                                + value
                                + "] at dim ["
                                + index
                                + "];"
                        );
                        break;
                    }
                }

                return errors;
            }

            @Override
            void checkVectorMagnitude(
                VectorSimilarity similarity,
                Function<StringBuilder, StringBuilder> appender,
                float squaredMagnitude
            ) {}

            @Override
            public double computeSquaredMagnitude(VectorData vectorData) {
                int count = 0;
                int i = 0;
                byte[] byteBits = vectorData.asByteVector();
                for (int upperBound = byteBits.length & -8; i < upperBound; i += 8) {
                    count += Long.bitCount((long) BitUtil.VH_NATIVE_LONG.get(byteBits, i));
                }

                while (i < byteBits.length) {
                    count += Integer.bitCount(byteBits[i] & 255);
                    ++i;
                }
                return count;
            }

            private VectorData parseVectorArray(
                DocumentParserContext context,
                int dims,
                IntBooleanConsumer dimChecker,
                VectorSimilarity similarity
            ) throws IOException {
                int index = 0;
                byte[] vector = new byte[dims / Byte.SIZE];
                for (XContentParser.Token token = context.parser().nextToken(); token != Token.END_ARRAY; token = context.parser()
                    .nextToken()) {
                    dimChecker.accept(index * Byte.SIZE, false);
                    ensureExpectedToken(Token.VALUE_NUMBER, token, context.parser());
                    final int value;
                    if (context.parser().numberType() != XContentParser.NumberType.INT) {
                        float floatValue = context.parser().floatValue(true);
                        if (floatValue % 1.0f != 0.0f) {
                            throw new IllegalArgumentException(
                                "element_type ["
                                    + this
                                    + "] vectors only support non-decimal values but found decimal value ["
                                    + floatValue
                                    + "] at dim ["
                                    + index
                                    + "];"
                            );
                        }
                        value = (int) floatValue;
                    } else {
                        value = context.parser().intValue(true);
                    }
                    if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
                        throw new IllegalArgumentException(
                            "element_type ["
                                + this
                                + "] vectors only support integers between ["
                                + Byte.MIN_VALUE
                                + ", "
                                + Byte.MAX_VALUE
                                + "] but found ["
                                + value
                                + "] at dim ["
                                + index
                                + "];"
                        );
                    }
                    vector[index++] = (byte) value;
                }
                dimChecker.accept(index * Byte.SIZE, true);
                return VectorData.fromBytes(vector);
            }

            private VectorData parseHexEncodedVector(DocumentParserContext context, IntBooleanConsumer dimChecker) throws IOException {
                byte[] decodedVector = HexFormat.of().parseHex(context.parser().text());
                dimChecker.accept(decodedVector.length * Byte.SIZE, true);
                return VectorData.fromBytes(decodedVector);
            }

            @Override
            public VectorData parseKnnVector(
                DocumentParserContext context,
                int dims,
                IntBooleanConsumer dimChecker,
                VectorSimilarity similarity
            ) throws IOException {
                XContentParser.Token token = context.parser().currentToken();
                return switch (token) {
                    case START_ARRAY -> parseVectorArray(context, dims, dimChecker, similarity);
                    case VALUE_STRING -> parseHexEncodedVector(context, dimChecker);
                    default -> throw new ParsingException(
                        context.parser().getTokenLocation(),
                        format("Unsupported type [%s] for provided value [%s]", token, context.parser().text())
                    );
                };
            }

            @Override
            public void parseKnnVectorAndIndex(DocumentParserContext context, DenseVectorFieldMapper fieldMapper) throws IOException {
                VectorData vectorData = parseKnnVector(context, fieldMapper.fieldType().dims, (i, end) -> {
                    if (end) {
                        fieldMapper.checkDimensionMatches(i, context);
                    } else {
                        fieldMapper.checkDimensionExceeded(i, context);
                    }
                }, fieldMapper.fieldType().similarity);
                Field field = createKnnVectorField(
                    fieldMapper.fieldType().name(),
                    vectorData.asByteVector(),
                    fieldMapper.fieldType().similarity.vectorSimilarityFunction(fieldMapper.indexCreatedVersion, this)
                );
                context.doc().addWithKey(fieldMapper.fieldType().name(), field);
            }

            @Override
            public int getNumBytes(int dimensions) {
                assert dimensions % Byte.SIZE == 0;
                return dimensions / Byte.SIZE;
            }

            @Override
            public ByteBuffer createByteBuffer(IndexVersion indexVersion, int numBytes) {
                return ByteBuffer.wrap(new byte[numBytes]);
            }

            @Override
            public int parseDimensionCount(DocumentParserContext context) throws IOException {
                XContentParser.Token currentToken = context.parser().currentToken();
                return switch (currentToken) {
                    case START_ARRAY -> {
                        int index = 0;
                        for (Token token = context.parser().nextToken(); token != Token.END_ARRAY; token = context.parser().nextToken()) {
                            index++;
                        }
                        yield index * Byte.SIZE;
                    }
                    case VALUE_STRING -> {
                        byte[] decodedVector = HexFormat.of().parseHex(context.parser().text());
                        yield decodedVector.length * Byte.SIZE;
                    }
                    default -> throw new ParsingException(
                        context.parser().getTokenLocation(),
                        format("Unsupported type [%s] for provided value [%s]", currentToken, context.parser().text())
                    );
                };
            }

            @Override
            public void checkDimensions(Integer dvDims, int qvDims) {
                if (dvDims != null && dvDims != qvDims * Byte.SIZE) {
                    throw new IllegalArgumentException(
                        "The query vector has a different number of dimensions ["
                            + qvDims * Byte.SIZE
                            + "] than the document vectors ["
                            + dvDims
                            + "]."
                    );
                }
            }
        };

        public abstract void writeValue(ByteBuffer byteBuffer, float value);

        public abstract void readAndWriteValue(ByteBuffer byteBuffer, XContentBuilder b) throws IOException;

        abstract IndexFieldData.Builder fielddataBuilder(DenseVectorFieldType denseVectorFieldType, FieldDataContext fieldDataContext);

        abstract void parseKnnVectorAndIndex(DocumentParserContext context, DenseVectorFieldMapper fieldMapper) throws IOException;

        public abstract VectorData parseKnnVector(
            DocumentParserContext context,
            int dims,
            IntBooleanConsumer dimChecker,
            VectorSimilarity similarity
        ) throws IOException;

        public abstract int getNumBytes(int dimensions);

        public abstract ByteBuffer createByteBuffer(IndexVersion indexVersion, int numBytes);

        /**
         * Checks the input {@code vector} is one of the {@code possibleTypes},
         * and returns the first type that it matches
         */
        public static ElementType checkValidVector(float[] vector, ElementType... possibleTypes) {
            assert possibleTypes.length != 0;
            // we're looking for one valid allowed type
            // assume the types are in order of specificity
            StringBuilder[] errors = new StringBuilder[possibleTypes.length];
            for (int i = 0; i < possibleTypes.length; i++) {
                StringBuilder error = possibleTypes[i].checkVectorErrors(vector);
                if (error == null) {
                    // this one works - use it
                    return possibleTypes[i];
                } else {
                    errors[i] = error;
                }
            }

            // oh dear, none of the possible types work with this vector. Generate the error message and throw.
            StringBuilder message = new StringBuilder();
            for (int i = 0; i < possibleTypes.length; i++) {
                if (i > 0) {
                    message.append(" ");
                }
                message.append("Vector is not a ").append(possibleTypes[i]).append(" vector: ").append(errors[i]);
            }
            throw new IllegalArgumentException(appendErrorElements(message, vector).toString());
        }

        public void checkVectorBounds(float[] vector) {
            StringBuilder errors = checkVectorErrors(vector);
            if (errors != null) {
                throw new IllegalArgumentException(appendErrorElements(errors, vector).toString());
            }
        }

        abstract StringBuilder checkVectorErrors(float[] vector);

        abstract void checkVectorMagnitude(
            VectorSimilarity similarity,
            Function<StringBuilder, StringBuilder> errorElementsAppender,
            float squaredMagnitude
        );

        public void checkDimensions(Integer dvDims, int qvDims) {
            if (dvDims != null && dvDims != qvDims) {
                throw new IllegalArgumentException(
                    "The query vector has a different number of dimensions [" + qvDims + "] than the document vectors [" + dvDims + "]."
                );
            }
        }

        public int parseDimensionCount(DocumentParserContext context) throws IOException {
            int index = 0;
            for (Token token = context.parser().nextToken(); token != Token.END_ARRAY; token = context.parser().nextToken()) {
                index++;
            }
            return index;
        }

        StringBuilder checkNanAndInfinite(float[] vector) {
            StringBuilder errorBuilder = null;

            for (int index = 0; index < vector.length; ++index) {
                float value = vector[index];

                if (Float.isNaN(value)) {
                    errorBuilder = new StringBuilder(
                        "element_type [" + this + "] vectors do not support NaN values but found [" + value + "] at dim [" + index + "];"
                    );
                    break;
                }

                if (Float.isInfinite(value)) {
                    errorBuilder = new StringBuilder(
                        "element_type ["
                            + this
                            + "] vectors do not support infinite values but found ["
                            + value
                            + "] at dim ["
                            + index
                            + "];"
                    );
                    break;
                }
            }

            return errorBuilder;
        }

        static StringBuilder appendErrorElements(StringBuilder errorBuilder, float[] vector) {
            // Include the first five elements of the invalid vector in the error message
            errorBuilder.append(" Preview of invalid vector: [");
            for (int i = 0; i < Math.min(5, vector.length); i++) {
                if (i > 0) {
                    errorBuilder.append(", ");
                }
                errorBuilder.append(vector[i]);
            }
            if (vector.length >= 5) {
                errorBuilder.append(", ...");
            }
            errorBuilder.append("]");
            return errorBuilder;
        }

        static StringBuilder appendErrorElements(StringBuilder errorBuilder, byte[] vector) {
            // Include the first five elements of the invalid vector in the error message
            errorBuilder.append(" Preview of invalid vector: [");
            for (int i = 0; i < Math.min(5, vector.length); i++) {
                if (i > 0) {
                    errorBuilder.append(", ");
                }
                errorBuilder.append(vector[i]);
            }
            if (vector.length >= 5) {
                errorBuilder.append(", ...");
            }
            errorBuilder.append("]");
            return errorBuilder;
        }

        static Function<StringBuilder, StringBuilder> errorFloatElementsAppender(float[] vector) {
            return sb -> appendErrorElements(sb, vector);
        }

        static Function<StringBuilder, StringBuilder> errorByteElementsAppender(byte[] vector) {
            return sb -> appendErrorElements(sb, vector);
        }

        public abstract double computeSquaredMagnitude(VectorData vectorData);

        public static ElementType fromString(String name) {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        }
    }

    public static final Map<String, ElementType> namesToElementType = Map.of(
        ElementType.BYTE.toString(),
        ElementType.BYTE,
        ElementType.FLOAT.toString(),
        ElementType.FLOAT,
        ElementType.BIT.toString(),
        ElementType.BIT
    );

    public enum VectorSimilarity {
        L2_NORM {
            @Override
            float score(float similarity, ElementType elementType, int dim) {
                return switch (elementType) {
                    case BYTE, FLOAT -> 1f / (1f + similarity * similarity);
                    case BIT -> (dim - similarity) / dim;
                };
            }

            @Override
            public VectorSimilarityFunction vectorSimilarityFunction(IndexVersion indexVersion, ElementType elementType) {
                return VectorSimilarityFunction.EUCLIDEAN;
            }
        },
        COSINE {
            @Override
            float score(float similarity, ElementType elementType, int dim) {
                assert elementType != ElementType.BIT;
                return switch (elementType) {
                    case BYTE, FLOAT -> (1 + similarity) / 2f;
                    default -> throw new IllegalArgumentException("Unsupported element type [" + elementType + "]");
                };
            }

            @Override
            public VectorSimilarityFunction vectorSimilarityFunction(IndexVersion indexVersion, ElementType elementType) {
                return indexVersion.onOrAfter(NORMALIZE_COSINE) && ElementType.FLOAT.equals(elementType)
                    ? VectorSimilarityFunction.DOT_PRODUCT
                    : VectorSimilarityFunction.COSINE;
            }
        },
        DOT_PRODUCT {
            @Override
            float score(float similarity, ElementType elementType, int dim) {
                return switch (elementType) {
                    case BYTE -> 0.5f + similarity / (float) (dim * (1 << 15));
                    case FLOAT -> (1 + similarity) / 2f;
                    default -> throw new IllegalArgumentException("Unsupported element type [" + elementType + "]");
                };
            }

            @Override
            public VectorSimilarityFunction vectorSimilarityFunction(IndexVersion indexVersion, ElementType elementType) {
                return VectorSimilarityFunction.DOT_PRODUCT;
            }
        },
        MAX_INNER_PRODUCT {
            @Override
            float score(float similarity, ElementType elementType, int dim) {
                return switch (elementType) {
                    case BYTE, FLOAT -> similarity < 0 ? 1 / (1 + -1 * similarity) : similarity + 1;
                    default -> throw new IllegalArgumentException("Unsupported element type [" + elementType + "]");
                };
            }

            @Override
            public VectorSimilarityFunction vectorSimilarityFunction(IndexVersion indexVersion, ElementType elementType) {
                return VectorSimilarityFunction.MAXIMUM_INNER_PRODUCT;
            }
        };

        @Override
        public final String toString() {
            return name().toLowerCase(Locale.ROOT);
        }

        abstract float score(float similarity, ElementType elementType, int dim);

        public abstract VectorSimilarityFunction vectorSimilarityFunction(IndexVersion indexVersion, ElementType elementType);
    }

    public abstract static class DenseVectorIndexOptions implements IndexOptions {
        final VectorIndexType type;

        DenseVectorIndexOptions(VectorIndexType type) {
            this.type = type;
        }

        abstract KnnVectorsFormat getVectorsFormat(ElementType elementType);

        public boolean validate(ElementType elementType, int dim, boolean throwOnError) {
            return validateElementType(elementType, throwOnError) && validateDimension(dim, throwOnError);
        }

        public boolean validateElementType(ElementType elementType) {
            return validateElementType(elementType, true);
        }

        final boolean validateElementType(ElementType elementType, boolean throwOnError) {
            boolean validElementType = type.supportsElementType(elementType);
            if (throwOnError && validElementType == false) {
                throw new IllegalArgumentException(
                    "[element_type] cannot be [" + elementType.toString() + "] when using index type [" + type + "]"
                );
            }
            return validElementType;
        }

        public abstract boolean updatableTo(DenseVectorIndexOptions update);

        public boolean validateDimension(int dim) {
            return validateDimension(dim, true);
        }

        public boolean validateDimension(int dim, boolean throwOnError) {
            boolean supportsDimension = type.supportsDimension(dim);
            if (throwOnError && supportsDimension == false) {
                throw new IllegalArgumentException(type.name + " only supports even dimensions; provided=" + dim);
            }
            return supportsDimension;
        }

        abstract boolean doEquals(DenseVectorIndexOptions other);

        abstract int doHashCode();

        public VectorIndexType getType() {
            return type;
        }

        @Override
        public final boolean equals(Object other) {
            if (other == this) {
                return true;
            }
            if (other == null || other.getClass() != getClass()) {
                return false;
            }
            DenseVectorIndexOptions otherOptions = (DenseVectorIndexOptions) other;
            return Objects.equals(type, otherOptions.type) && doEquals(otherOptions);
        }

        @Override
        public final int hashCode() {
            return Objects.hash(type, doHashCode());
        }

        /**
         * Indicates whether the underlying vector search is performed using a flat (exhaustive) approach.
         * <p>
         * When {@code true}, it means the search does not use any approximate nearest neighbor (ANN)
         * acceleration structures such as HNSW or IVF. Instead, it performs a brute-force comparison
         * against all candidate vectors. This information can be used by higher-level components
         * to decide whether additional acceleration or optimization is necessary.
         *
         * @return {@code true} if the vector search is flat (exhaustive), {@code false} if it uses ANN structures
         */
        abstract boolean isFlat();
    }

    abstract static class QuantizedIndexOptions extends DenseVectorIndexOptions {
        final RescoreVector rescoreVector;

        QuantizedIndexOptions(VectorIndexType type, RescoreVector rescoreVector) {
            super(type);
            this.rescoreVector = rescoreVector;
        }
    }

    public enum VectorIndexType {
        HNSW("hnsw", false) {
            @Override
            public DenseVectorIndexOptions parseIndexOptions(String fieldName, Map<String, ?> indexOptionsMap, IndexVersion indexVersion) {
                Object mNode = indexOptionsMap.remove("m");
                Object efConstructionNode = indexOptionsMap.remove("ef_construction");
                if (mNode == null) {
                    mNode = Lucene99HnswVectorsFormat.DEFAULT_MAX_CONN;
                }
                if (efConstructionNode == null) {
                    efConstructionNode = Lucene99HnswVectorsFormat.DEFAULT_BEAM_WIDTH;
                }

                int m = XContentMapValues.nodeIntegerValue(mNode);
                int efConstruction = XContentMapValues.nodeIntegerValue(efConstructionNode);
                MappingParser.checkNoRemainingFields(fieldName, indexOptionsMap);
                return new HnswIndexOptions(m, efConstruction);
            }

            @Override
            public boolean supportsElementType(ElementType elementType) {
                return true;
            }

            @Override
            public boolean supportsDimension(int dims) {
                return true;
            }
        },
        INT8_HNSW("int8_hnsw", true) {
            @Override
            public DenseVectorIndexOptions parseIndexOptions(String fieldName, Map<String, ?> indexOptionsMap, IndexVersion indexVersion) {
                Object mNode = indexOptionsMap.remove("m");
                Object efConstructionNode = indexOptionsMap.remove("ef_construction");
                Object confidenceIntervalNode = indexOptionsMap.remove("confidence_interval");
                if (mNode == null) {
                    mNode = Lucene99HnswVectorsFormat.DEFAULT_MAX_CONN;
                }
                if (efConstructionNode == null) {
                    efConstructionNode = Lucene99HnswVectorsFormat.DEFAULT_BEAM_WIDTH;
                }
                int m = XContentMapValues.nodeIntegerValue(mNode);
                int efConstruction = XContentMapValues.nodeIntegerValue(efConstructionNode);
                Float confidenceInterval = null;
                if (confidenceIntervalNode != null) {
                    confidenceInterval = (float) XContentMapValues.nodeDoubleValue(confidenceIntervalNode);
                }
                RescoreVector rescoreVector = null;
                if (hasRescoreIndexVersion(indexVersion)) {
                    rescoreVector = RescoreVector.fromIndexOptions(indexOptionsMap, indexVersion);
                }
                MappingParser.checkNoRemainingFields(fieldName, indexOptionsMap);
                return new Int8HnswIndexOptions(m, efConstruction, confidenceInterval, rescoreVector);
            }

            @Override
            public boolean supportsElementType(ElementType elementType) {
                return elementType == ElementType.FLOAT;
            }

            @Override
            public boolean supportsDimension(int dims) {
                return true;
            }
        },
        INT4_HNSW("int4_hnsw", true) {
            public DenseVectorIndexOptions parseIndexOptions(String fieldName, Map<String, ?> indexOptionsMap, IndexVersion indexVersion) {
                Object mNode = indexOptionsMap.remove("m");
                Object efConstructionNode = indexOptionsMap.remove("ef_construction");
                Object confidenceIntervalNode = indexOptionsMap.remove("confidence_interval");
                if (mNode == null) {
                    mNode = Lucene99HnswVectorsFormat.DEFAULT_MAX_CONN;
                }
                if (efConstructionNode == null) {
                    efConstructionNode = Lucene99HnswVectorsFormat.DEFAULT_BEAM_WIDTH;
                }
                int m = XContentMapValues.nodeIntegerValue(mNode);
                int efConstruction = XContentMapValues.nodeIntegerValue(efConstructionNode);
                Float confidenceInterval = null;
                if (confidenceIntervalNode != null) {
                    confidenceInterval = (float) XContentMapValues.nodeDoubleValue(confidenceIntervalNode);
                }
                RescoreVector rescoreVector = null;
                if (hasRescoreIndexVersion(indexVersion)) {
                    rescoreVector = RescoreVector.fromIndexOptions(indexOptionsMap, indexVersion);
                }
                MappingParser.checkNoRemainingFields(fieldName, indexOptionsMap);
                return new Int4HnswIndexOptions(m, efConstruction, confidenceInterval, rescoreVector);
            }

            @Override
            public boolean supportsElementType(ElementType elementType) {
                return elementType == ElementType.FLOAT;
            }

            @Override
            public boolean supportsDimension(int dims) {
                return dims % 2 == 0;
            }
        },
        FLAT("flat", false) {
            @Override
            public DenseVectorIndexOptions parseIndexOptions(String fieldName, Map<String, ?> indexOptionsMap, IndexVersion indexVersion) {
                MappingParser.checkNoRemainingFields(fieldName, indexOptionsMap);
                return new FlatIndexOptions();
            }

            @Override
            public boolean supportsElementType(ElementType elementType) {
                return true;
            }

            @Override
            public boolean supportsDimension(int dims) {
                return true;
            }
        },
        INT8_FLAT("int8_flat", true) {
            @Override
            public DenseVectorIndexOptions parseIndexOptions(String fieldName, Map<String, ?> indexOptionsMap, IndexVersion indexVersion) {
                Object confidenceIntervalNode = indexOptionsMap.remove("confidence_interval");
                Float confidenceInterval = null;
                if (confidenceIntervalNode != null) {
                    confidenceInterval = (float) XContentMapValues.nodeDoubleValue(confidenceIntervalNode);
                }
                RescoreVector rescoreVector = null;
                if (hasRescoreIndexVersion(indexVersion)) {
                    rescoreVector = RescoreVector.fromIndexOptions(indexOptionsMap, indexVersion);
                }
                MappingParser.checkNoRemainingFields(fieldName, indexOptionsMap);
                return new Int8FlatIndexOptions(confidenceInterval, rescoreVector);
            }

            @Override
            public boolean supportsElementType(ElementType elementType) {
                return elementType == ElementType.FLOAT;
            }

            @Override
            public boolean supportsDimension(int dims) {
                return true;
            }
        },
        INT4_FLAT("int4_flat", true) {
            @Override
            public DenseVectorIndexOptions parseIndexOptions(String fieldName, Map<String, ?> indexOptionsMap, IndexVersion indexVersion) {
                Object confidenceIntervalNode = indexOptionsMap.remove("confidence_interval");
                Float confidenceInterval = null;
                if (confidenceIntervalNode != null) {
                    confidenceInterval = (float) XContentMapValues.nodeDoubleValue(confidenceIntervalNode);
                }
                RescoreVector rescoreVector = null;
                if (hasRescoreIndexVersion(indexVersion)) {
                    rescoreVector = RescoreVector.fromIndexOptions(indexOptionsMap, indexVersion);
                }
                MappingParser.checkNoRemainingFields(fieldName, indexOptionsMap);
                return new Int4FlatIndexOptions(confidenceInterval, rescoreVector);
            }

            @Override
            public boolean supportsElementType(ElementType elementType) {
                return elementType == ElementType.FLOAT;
            }

            @Override
            public boolean supportsDimension(int dims) {
                return dims % 2 == 0;
            }
        },
        BBQ_HNSW("bbq_hnsw", true) {
            @Override
            public DenseVectorIndexOptions parseIndexOptions(String fieldName, Map<String, ?> indexOptionsMap, IndexVersion indexVersion) {
                Object mNode = indexOptionsMap.remove("m");
                Object efConstructionNode = indexOptionsMap.remove("ef_construction");
                if (mNode == null) {
                    mNode = Lucene99HnswVectorsFormat.DEFAULT_MAX_CONN;
                }
                if (efConstructionNode == null) {
                    efConstructionNode = Lucene99HnswVectorsFormat.DEFAULT_BEAM_WIDTH;
                }
                int m = XContentMapValues.nodeIntegerValue(mNode);
                int efConstruction = XContentMapValues.nodeIntegerValue(efConstructionNode);
                RescoreVector rescoreVector = null;
                if (hasRescoreIndexVersion(indexVersion)) {
                    rescoreVector = RescoreVector.fromIndexOptions(indexOptionsMap, indexVersion);
                    if (rescoreVector == null && defaultOversampleForBBQ(indexVersion)) {
                        rescoreVector = new RescoreVector(DEFAULT_OVERSAMPLE);
                    }
                }
                MappingParser.checkNoRemainingFields(fieldName, indexOptionsMap);
                return new BBQHnswIndexOptions(m, efConstruction, rescoreVector);
            }

            @Override
            public boolean supportsElementType(ElementType elementType) {
                return elementType == ElementType.FLOAT;
            }

            @Override
            public boolean supportsDimension(int dims) {
                return dims >= BBQ_MIN_DIMS;
            }
        },
        BBQ_FLAT("bbq_flat", true) {
            @Override
            public DenseVectorIndexOptions parseIndexOptions(String fieldName, Map<String, ?> indexOptionsMap, IndexVersion indexVersion) {
                RescoreVector rescoreVector = null;
                if (hasRescoreIndexVersion(indexVersion)) {
                    rescoreVector = RescoreVector.fromIndexOptions(indexOptionsMap, indexVersion);
                    if (rescoreVector == null && defaultOversampleForBBQ(indexVersion)) {
                        rescoreVector = new RescoreVector(DEFAULT_OVERSAMPLE);
                    }
                }
                MappingParser.checkNoRemainingFields(fieldName, indexOptionsMap);
                return new BBQFlatIndexOptions(rescoreVector);
            }

            @Override
            public boolean supportsElementType(ElementType elementType) {
                return elementType == ElementType.FLOAT;
            }

            @Override
            public boolean supportsDimension(int dims) {
                return dims >= BBQ_MIN_DIMS;
            }
        },
        BBQ_DISK("bbq_disk", true) {
            @Override
            public DenseVectorIndexOptions parseIndexOptions(String fieldName, Map<String, ?> indexOptionsMap, IndexVersion indexVersion) {
                Object clusterSizeNode = indexOptionsMap.remove("cluster_size");
                int clusterSize = IVFVectorsFormat.DEFAULT_VECTORS_PER_CLUSTER;
                if (clusterSizeNode != null) {
                    clusterSize = XContentMapValues.nodeIntegerValue(clusterSizeNode);
                    if (clusterSize < MIN_VECTORS_PER_CLUSTER || clusterSize > MAX_VECTORS_PER_CLUSTER) {
                        throw new IllegalArgumentException(
                            "cluster_size must be between "
                                + MIN_VECTORS_PER_CLUSTER
                                + " and "
                                + MAX_VECTORS_PER_CLUSTER
                                + ", got: "
                                + clusterSize
                        );
                    }
                }
                RescoreVector rescoreVector = RescoreVector.fromIndexOptions(indexOptionsMap, indexVersion);
                if (rescoreVector == null) {
                    rescoreVector = new RescoreVector(DEFAULT_OVERSAMPLE);
                }
                Object nProbeNode = indexOptionsMap.remove("default_n_probe");
                int nProbe = -1;
                if (nProbeNode != null) {
                    nProbe = XContentMapValues.nodeIntegerValue(nProbeNode);
                    if (nProbe < 1 && nProbe != -1) {
                        throw new IllegalArgumentException(
                            "default_n_probe must be at least 1 or exactly -1, got: " + nProbe + " for field [" + fieldName + "]"
                        );
                    }
                }
                MappingParser.checkNoRemainingFields(fieldName, indexOptionsMap);
                return new BBQIVFIndexOptions(clusterSize, nProbe, rescoreVector);
            }

            @Override
            public boolean supportsElementType(ElementType elementType) {
                return elementType == ElementType.FLOAT;
            }

            @Override
            public boolean supportsDimension(int dims) {
                return true;
            }

            @Override
            public boolean isEnabled() {
                return IVF_FORMAT.isEnabled();
            }
        };

        public static Optional<VectorIndexType> fromString(String type) {
            return Stream.of(VectorIndexType.values())
                .filter(VectorIndexType::isEnabled)
                .filter(vectorIndexType -> vectorIndexType.name.equals(type))
                .findFirst();
        }

        private final String name;
        private final boolean quantized;

        VectorIndexType(String name, boolean quantized) {
            this.name = name;
            this.quantized = quantized;
        }

        public abstract DenseVectorIndexOptions parseIndexOptions(
            String fieldName,
            Map<String, ?> indexOptionsMap,
            IndexVersion indexVersion
        );

        public abstract boolean supportsElementType(ElementType elementType);

        public abstract boolean supportsDimension(int dims);

        public boolean isQuantized() {
            return quantized;
        }

        public boolean isEnabled() {
            return true;
        }

        public String getName() {
            return name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static class Int8FlatIndexOptions extends QuantizedIndexOptions {
        private final Float confidenceInterval;

        Int8FlatIndexOptions(Float confidenceInterval, RescoreVector rescoreVector) {
            super(VectorIndexType.INT8_FLAT, rescoreVector);
            this.confidenceInterval = confidenceInterval;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field("type", type);
            if (confidenceInterval != null) {
                builder.field("confidence_interval", confidenceInterval);
            }
            if (rescoreVector != null) {
                rescoreVector.toXContent(builder, params);
            }
            builder.endObject();
            return builder;
        }

        @Override
        KnnVectorsFormat getVectorsFormat(ElementType elementType) {
            assert elementType == ElementType.FLOAT;
            return new ES813Int8FlatVectorFormat(confidenceInterval, 7, false);
        }

        @Override
        boolean doEquals(DenseVectorIndexOptions o) {
            Int8FlatIndexOptions that = (Int8FlatIndexOptions) o;
            return Objects.equals(confidenceInterval, that.confidenceInterval) && Objects.equals(rescoreVector, that.rescoreVector);
        }

        @Override
        int doHashCode() {
            return Objects.hash(confidenceInterval, rescoreVector);
        }

        @Override
        boolean isFlat() {
            return true;
        }

        @Override
        public boolean updatableTo(DenseVectorIndexOptions update) {
            return update.type.equals(this.type)
                || update.type.equals(VectorIndexType.HNSW)
                || update.type.equals(VectorIndexType.INT8_HNSW)
                || update.type.equals(VectorIndexType.INT4_HNSW)
                || update.type.equals(VectorIndexType.BBQ_HNSW)
                || update.type.equals(VectorIndexType.INT4_FLAT)
                || update.type.equals(VectorIndexType.BBQ_FLAT)
                || update.type.equals(VectorIndexType.BBQ_DISK);
        }
    }

    static class FlatIndexOptions extends DenseVectorIndexOptions {

        FlatIndexOptions() {
            super(VectorIndexType.FLAT);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field("type", type);
            builder.endObject();
            return builder;
        }

        @Override
        KnnVectorsFormat getVectorsFormat(ElementType elementType) {
            if (elementType.equals(ElementType.BIT)) {
                return new ES815BitFlatVectorFormat();
            }
            return new ES813FlatVectorFormat();
        }

        @Override
        public boolean updatableTo(DenseVectorIndexOptions update) {
            return true;
        }

        @Override
        public boolean doEquals(DenseVectorIndexOptions o) {
            return o instanceof FlatIndexOptions;
        }

        @Override
        public int doHashCode() {
            return Objects.hash(type);
        }

        @Override
        boolean isFlat() {
            return true;
        }
    }

    public static class Int4HnswIndexOptions extends QuantizedIndexOptions {
        private final int m;
        private final int efConstruction;
        private final float confidenceInterval;

        public Int4HnswIndexOptions(int m, int efConstruction, Float confidenceInterval, RescoreVector rescoreVector) {
            super(VectorIndexType.INT4_HNSW, rescoreVector);
            this.m = m;
            this.efConstruction = efConstruction;
            // The default confidence interval for int4 is dynamic quantiles, this provides the best relevancy and is
            // effectively required for int4 to behave well across a wide range of data.
            this.confidenceInterval = confidenceInterval == null ? 0f : confidenceInterval;
        }

        @Override
        public KnnVectorsFormat getVectorsFormat(ElementType elementType) {
            assert elementType == ElementType.FLOAT;
            return new ES814HnswScalarQuantizedVectorsFormat(m, efConstruction, confidenceInterval, 4, true);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field("type", type);
            builder.field("m", m);
            builder.field("ef_construction", efConstruction);
            builder.field("confidence_interval", confidenceInterval);
            if (rescoreVector != null) {
                rescoreVector.toXContent(builder, params);
            }
            builder.endObject();
            return builder;
        }

        @Override
        public boolean doEquals(DenseVectorIndexOptions o) {
            Int4HnswIndexOptions that = (Int4HnswIndexOptions) o;
            return m == that.m
                && efConstruction == that.efConstruction
                && Objects.equals(confidenceInterval, that.confidenceInterval)
                && Objects.equals(rescoreVector, that.rescoreVector);
        }

        @Override
        public int doHashCode() {
            return Objects.hash(m, efConstruction, confidenceInterval, rescoreVector);
        }

        @Override
        boolean isFlat() {
            return false;
        }

        @Override
        public String toString() {
            return "{type="
                + type
                + ", m="
                + m
                + ", ef_construction="
                + efConstruction
                + ", confidence_interval="
                + confidenceInterval
                + ", rescore_vector="
                + (rescoreVector == null ? "none" : rescoreVector)
                + "}";
        }

        @Override
        public boolean updatableTo(DenseVectorIndexOptions update) {
            boolean updatable = false;
            if (update.type.equals(VectorIndexType.INT4_HNSW)) {
                Int4HnswIndexOptions int4HnswIndexOptions = (Int4HnswIndexOptions) update;
                // fewer connections would break assumptions on max number of connections (based on largest previous graph) during merge
                // quantization could not behave as expected with different confidence intervals (and quantiles) to be created
                updatable = int4HnswIndexOptions.m >= this.m && confidenceInterval == int4HnswIndexOptions.confidenceInterval;
            } else if (update.type.equals(VectorIndexType.BBQ_HNSW)) {
                updatable = ((BBQHnswIndexOptions) update).m >= m;
            } else {
                updatable = update.type.equals(VectorIndexType.BBQ_DISK);
            }
            return updatable;
        }
    }

    static class Int4FlatIndexOptions extends QuantizedIndexOptions {
        private final float confidenceInterval;

        Int4FlatIndexOptions(Float confidenceInterval, RescoreVector rescoreVector) {
            super(VectorIndexType.INT4_FLAT, rescoreVector);
            // The default confidence interval for int4 is dynamic quantiles, this provides the best relevancy and is
            // effectively required for int4 to behave well across a wide range of data.
            this.confidenceInterval = confidenceInterval == null ? 0f : confidenceInterval;
        }

        @Override
        public KnnVectorsFormat getVectorsFormat(ElementType elementType) {
            assert elementType == ElementType.FLOAT;
            return new ES813Int8FlatVectorFormat(confidenceInterval, 4, true);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field("type", type);
            builder.field("confidence_interval", confidenceInterval);
            if (rescoreVector != null) {
                rescoreVector.toXContent(builder, params);
            }
            builder.endObject();
            return builder;
        }

        @Override
        public boolean doEquals(DenseVectorIndexOptions o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Int4FlatIndexOptions that = (Int4FlatIndexOptions) o;
            return Objects.equals(confidenceInterval, that.confidenceInterval) && Objects.equals(rescoreVector, that.rescoreVector);
        }

        @Override
        public int doHashCode() {
            return Objects.hash(confidenceInterval, rescoreVector);
        }

        @Override
        boolean isFlat() {
            return true;
        }

        @Override
        public String toString() {
            return "{type=" + type + ", confidence_interval=" + confidenceInterval + ", rescore_vector=" + rescoreVector + "}";
        }

        @Override
        public boolean updatableTo(DenseVectorIndexOptions update) {
            // TODO: add support for updating from flat, hnsw, and int8_hnsw and updating params
            return update.type.equals(this.type)
                || update.type.equals(VectorIndexType.HNSW)
                || update.type.equals(VectorIndexType.INT8_HNSW)
                || update.type.equals(VectorIndexType.INT4_HNSW)
                || update.type.equals(VectorIndexType.BBQ_HNSW)
                || update.type.equals(VectorIndexType.BBQ_FLAT)
                || update.type.equals(VectorIndexType.BBQ_DISK);
        }

    }

    public static class Int8HnswIndexOptions extends QuantizedIndexOptions {
        private final int m;
        private final int efConstruction;
        private final Float confidenceInterval;

        public Int8HnswIndexOptions(int m, int efConstruction, Float confidenceInterval, RescoreVector rescoreVector) {
            super(VectorIndexType.INT8_HNSW, rescoreVector);
            this.m = m;
            this.efConstruction = efConstruction;
            this.confidenceInterval = confidenceInterval;
        }

        @Override
        public KnnVectorsFormat getVectorsFormat(ElementType elementType) {
            assert elementType == ElementType.FLOAT;
            return new ES814HnswScalarQuantizedVectorsFormat(m, efConstruction, confidenceInterval, 7, false);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field("type", type);
            builder.field("m", m);
            builder.field("ef_construction", efConstruction);
            if (confidenceInterval != null) {
                builder.field("confidence_interval", confidenceInterval);
            }
            if (rescoreVector != null) {
                rescoreVector.toXContent(builder, params);
            }
            builder.endObject();
            return builder;
        }

        @Override
        public boolean doEquals(DenseVectorIndexOptions o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Int8HnswIndexOptions that = (Int8HnswIndexOptions) o;
            return m == that.m
                && efConstruction == that.efConstruction
                && Objects.equals(confidenceInterval, that.confidenceInterval)
                && Objects.equals(rescoreVector, that.rescoreVector);
        }

        @Override
        public int doHashCode() {
            return Objects.hash(m, efConstruction, confidenceInterval, rescoreVector);
        }

        @Override
        boolean isFlat() {
            return false;
        }

        @Override
        public String toString() {
            return "{type="
                + type
                + ", m="
                + m
                + ", ef_construction="
                + efConstruction
                + ", confidence_interval="
                + confidenceInterval
                + ", rescore_vector="
                + (rescoreVector == null ? "none" : rescoreVector)
                + "}";
        }

        @Override
        public boolean updatableTo(DenseVectorIndexOptions update) {
            boolean updatable;
            if (update.type.equals(this.type)) {
                Int8HnswIndexOptions int8HnswIndexOptions = (Int8HnswIndexOptions) update;
                // fewer connections would break assumptions on max number of connections (based on largest previous graph) during merge
                // quantization could not behave as expected with different confidence intervals (and quantiles) to be created
                updatable = int8HnswIndexOptions.m >= this.m;
                updatable &= confidenceInterval == null
                    || int8HnswIndexOptions.confidenceInterval != null
                        && confidenceInterval.equals(int8HnswIndexOptions.confidenceInterval);
            } else {
                updatable = update.type.equals(VectorIndexType.BBQ_DISK)
                    || update.type.equals(VectorIndexType.INT4_HNSW) && ((Int4HnswIndexOptions) update).m >= this.m
                    || (update.type.equals(VectorIndexType.BBQ_HNSW) && ((BBQHnswIndexOptions) update).m >= m);
            }
            return updatable;
        }
    }

    static class HnswIndexOptions extends DenseVectorIndexOptions {
        private final int m;
        private final int efConstruction;

        HnswIndexOptions(int m, int efConstruction) {
            super(VectorIndexType.HNSW);
            this.m = m;
            this.efConstruction = efConstruction;
        }

        @Override
        public KnnVectorsFormat getVectorsFormat(ElementType elementType) {
            if (elementType == ElementType.BIT) {
                return new ES815HnswBitVectorsFormat(m, efConstruction);
            }
            return new Lucene99HnswVectorsFormat(m, efConstruction, 1, null);
        }

        @Override
        public boolean updatableTo(DenseVectorIndexOptions update) {
            boolean updatable = update.type.equals(this.type);
            if (updatable) {
                // fewer connections would break assumptions on max number of connections (based on largest previous graph) during merge
                HnswIndexOptions hnswIndexOptions = (HnswIndexOptions) update;
                updatable = hnswIndexOptions.m >= this.m;
            }
            return updatable
                || update.type.equals(VectorIndexType.BBQ_DISK)
                || (update.type.equals(VectorIndexType.INT8_HNSW) && ((Int8HnswIndexOptions) update).m >= m)
                || (update.type.equals(VectorIndexType.INT4_HNSW) && ((Int4HnswIndexOptions) update).m >= m)
                || (update.type.equals(VectorIndexType.BBQ_HNSW) && ((BBQHnswIndexOptions) update).m >= m);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field("type", type);
            builder.field("m", m);
            builder.field("ef_construction", efConstruction);
            builder.endObject();
            return builder;
        }

        @Override
        public boolean doEquals(DenseVectorIndexOptions o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            HnswIndexOptions that = (HnswIndexOptions) o;
            return m == that.m && efConstruction == that.efConstruction;
        }

        @Override
        public int doHashCode() {
            return Objects.hash(m, efConstruction);
        }

        @Override
        boolean isFlat() {
            return false;
        }

        @Override
        public String toString() {
            return "{type=" + type + ", m=" + m + ", ef_construction=" + efConstruction + "}";
        }
    }

    public static class BBQHnswIndexOptions extends QuantizedIndexOptions {
        private final int m;
        private final int efConstruction;

        public BBQHnswIndexOptions(int m, int efConstruction, RescoreVector rescoreVector) {
            super(VectorIndexType.BBQ_HNSW, rescoreVector);
            this.m = m;
            this.efConstruction = efConstruction;
        }

        @Override
        KnnVectorsFormat getVectorsFormat(ElementType elementType) {
            assert elementType == ElementType.FLOAT;
            return new ES818HnswBinaryQuantizedVectorsFormat(m, efConstruction);
        }

        @Override
        public boolean updatableTo(DenseVectorIndexOptions update) {
            return (update.type.equals(this.type) && ((BBQHnswIndexOptions) update).m >= this.m)
                || update.type.equals(VectorIndexType.BBQ_DISK);
        }

        @Override
        boolean doEquals(DenseVectorIndexOptions other) {
            BBQHnswIndexOptions that = (BBQHnswIndexOptions) other;
            return m == that.m && efConstruction == that.efConstruction && Objects.equals(rescoreVector, that.rescoreVector);
        }

        @Override
        int doHashCode() {
            return Objects.hash(m, efConstruction, rescoreVector);
        }

        @Override
        boolean isFlat() {
            return false;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field("type", type);
            builder.field("m", m);
            builder.field("ef_construction", efConstruction);
            if (rescoreVector != null) {
                rescoreVector.toXContent(builder, params);
            }
            builder.endObject();
            return builder;
        }

        @Override
        public boolean validateDimension(int dim, boolean throwOnError) {
            boolean supportsDimension = type.supportsDimension(dim);
            if (throwOnError && supportsDimension == false) {
                throw new IllegalArgumentException(
                    type.name + " does not support dimensions fewer than " + BBQ_MIN_DIMS + "; provided=" + dim
                );
            }
            return supportsDimension;
        }
    }

    static class BBQFlatIndexOptions extends QuantizedIndexOptions {
        private final int CLASS_NAME_HASH = this.getClass().getName().hashCode();

        BBQFlatIndexOptions(RescoreVector rescoreVector) {
            super(VectorIndexType.BBQ_FLAT, rescoreVector);
        }

        @Override
        KnnVectorsFormat getVectorsFormat(ElementType elementType) {
            assert elementType == ElementType.FLOAT;
            return new ES818BinaryQuantizedVectorsFormat();
        }

        @Override
        public boolean updatableTo(DenseVectorIndexOptions update) {
            return update.type.equals(this.type)
                || update.type.equals(VectorIndexType.BBQ_HNSW)
                || update.type.equals(VectorIndexType.BBQ_DISK);
        }

        @Override
        boolean doEquals(DenseVectorIndexOptions other) {
            return other instanceof BBQFlatIndexOptions;
        }

        @Override
        int doHashCode() {
            return CLASS_NAME_HASH;
        }

        @Override
        boolean isFlat() {
            return true;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field("type", type);
            if (rescoreVector != null) {
                rescoreVector.toXContent(builder, params);
            }
            builder.endObject();
            return builder;
        }

        @Override
        public boolean validateDimension(int dim, boolean throwOnError) {
            boolean supportsDimension = type.supportsDimension(dim);
            if (throwOnError && supportsDimension == false) {
                throw new IllegalArgumentException(
                    type.name + " does not support dimensions fewer than " + BBQ_MIN_DIMS + "; provided=" + dim
                );
            }
            return supportsDimension;
        }

    }

    static class BBQIVFIndexOptions extends QuantizedIndexOptions {
        final int clusterSize;
        final int defaultNProbe;

        BBQIVFIndexOptions(int clusterSize, int defaultNProbe, RescoreVector rescoreVector) {
            super(VectorIndexType.BBQ_DISK, rescoreVector);
            this.clusterSize = clusterSize;
            this.defaultNProbe = defaultNProbe;
        }

        @Override
        KnnVectorsFormat getVectorsFormat(ElementType elementType) {
            assert elementType == ElementType.FLOAT;
            return new IVFVectorsFormat(clusterSize, IVFVectorsFormat.DEFAULT_CENTROIDS_PER_PARENT_CLUSTER);
        }

        @Override
        public boolean updatableTo(DenseVectorIndexOptions update) {
            return update.type.equals(this.type);
        }

        @Override
        boolean doEquals(DenseVectorIndexOptions other) {
            BBQIVFIndexOptions that = (BBQIVFIndexOptions) other;
            return clusterSize == that.clusterSize
                && defaultNProbe == that.defaultNProbe
                && Objects.equals(rescoreVector, that.rescoreVector);
        }

        @Override
        int doHashCode() {
            return Objects.hash(clusterSize, defaultNProbe, rescoreVector);
        }

        @Override
        boolean isFlat() {
            return false;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field("type", type);
            builder.field("cluster_size", clusterSize);
            builder.field("default_n_probe", defaultNProbe);
            if (rescoreVector != null) {
                rescoreVector.toXContent(builder, params);
            }
            builder.endObject();
            return builder;
        }
    }

    public record RescoreVector(float oversample) implements ToXContentObject {
        static final String NAME = "rescore_vector";
        static final String OVERSAMPLE = "oversample";

        static RescoreVector fromIndexOptions(Map<String, ?> indexOptionsMap, IndexVersion indexVersion) {
            Object rescoreVectorNode = indexOptionsMap.remove(NAME);
            if (rescoreVectorNode == null) {
                return null;
            }
            Map<String, Object> mappedNode = XContentMapValues.nodeMapValue(rescoreVectorNode, NAME);
            Object oversampleNode = mappedNode.get(OVERSAMPLE);
            if (oversampleNode == null) {
                throw new IllegalArgumentException("Invalid rescore_vector value. Missing required field " + OVERSAMPLE);
            }
            float oversampleValue = (float) XContentMapValues.nodeDoubleValue(oversampleNode);
            if (oversampleValue == 0 && allowsZeroRescore(indexVersion) == false) {
                throw new IllegalArgumentException("oversample must be greater than 1");
            }
            if (oversampleValue < 1 && oversampleValue != 0) {
                throw new IllegalArgumentException("oversample must be greater than 1 or exactly 0");
            } else if (oversampleValue > 10) {
                throw new IllegalArgumentException("oversample must be less than or equal to 10");
            }
            return new RescoreVector(oversampleValue);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject(NAME);
            builder.field(OVERSAMPLE, oversample);
            builder.endObject();
            return builder;
        }
    }

    public static final TypeParser PARSER = new TypeParser(
        (n, c) -> new Builder(
            n,
            c.getIndexSettings().getIndexVersionCreated(),
            INDEX_MAPPING_SOURCE_SYNTHETIC_VECTORS_SETTING.get(c.getIndexSettings().getSettings())
        ),
        notInMultiFields(CONTENT_TYPE)
    );

    public static final class DenseVectorFieldType extends SimpleMappedFieldType {
        private final ElementType elementType;
        private final Integer dims;
        private final boolean indexed;
        private final VectorSimilarity similarity;
        private final IndexVersion indexVersionCreated;
        private final DenseVectorIndexOptions indexOptions;
        private final boolean isSyntheticSource;

        public DenseVectorFieldType(
            String name,
            IndexVersion indexVersionCreated,
            ElementType elementType,
            Integer dims,
            boolean indexed,
            VectorSimilarity similarity,
            DenseVectorIndexOptions indexOptions,
            Map<String, String> meta,
            boolean isSyntheticSource
        ) {
            super(name, indexed, false, indexed == false, TextSearchInfo.NONE, meta);
            this.elementType = elementType;
            this.dims = dims;
            this.indexed = indexed;
            this.similarity = similarity;
            this.indexVersionCreated = indexVersionCreated;
            this.indexOptions = indexOptions;
            this.isSyntheticSource = isSyntheticSource;
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

        @Override
        public ValueFetcher valueFetcher(SearchExecutionContext context, String format) {
            if (format != null) {
                throw new IllegalArgumentException("Field [" + name() + "] of type [" + typeName() + "] doesn't support formats.");
            }
            return new ArraySourceValueFetcher(name(), context) {
                @Override
                protected Object parseSourceValue(Object value) {
                    return value;
                }
            };
        }

        @Override
        public DocValueFormat docValueFormat(String format, ZoneId timeZone) {
            return DocValueFormat.DENSE_VECTOR;
        }

        @Override
        public boolean isAggregatable() {
            return false;
        }

        @Override
        public boolean isVectorEmbedding() {
            return true;
        }

        @Override
        public IndexFieldData.Builder fielddataBuilder(FieldDataContext fieldDataContext) {
            return elementType.fielddataBuilder(this, fieldDataContext);
        }

        @Override
        public Query existsQuery(SearchExecutionContext context) {
            return new FieldExistsQuery(name());
        }

        @Override
        public Query termQuery(Object value, SearchExecutionContext context) {
            throw new IllegalArgumentException("Field [" + name() + "] of type [" + typeName() + "] doesn't support term queries");
        }

        public Query createExactKnnQuery(VectorData queryVector, Float vectorSimilarity) {
            if (isIndexed() == false) {
                throw new IllegalArgumentException(
                    "to perform knn search on field [" + name() + "], its mapping must have [index] set to [true]"
                );
            }
            Query knnQuery = switch (elementType) {
                case BYTE -> createExactKnnByteQuery(queryVector.asByteVector());
                case FLOAT -> createExactKnnFloatQuery(queryVector.asFloatVector());
                case BIT -> createExactKnnBitQuery(queryVector.asByteVector());
            };
            if (vectorSimilarity != null) {
                knnQuery = new VectorSimilarityQuery(knnQuery, vectorSimilarity, similarity.score(vectorSimilarity, elementType, dims));
            }
            return knnQuery;
        }

        private Query createExactKnnBitQuery(byte[] queryVector) {
            elementType.checkDimensions(dims, queryVector.length);
            return new DenseVectorQuery.Bytes(queryVector, name());
        }

        private Query createExactKnnByteQuery(byte[] queryVector) {
            elementType.checkDimensions(dims, queryVector.length);
            if (similarity == VectorSimilarity.DOT_PRODUCT || similarity == VectorSimilarity.COSINE) {
                float squaredMagnitude = VectorUtil.dotProduct(queryVector, queryVector);
                elementType.checkVectorMagnitude(similarity, ElementType.errorByteElementsAppender(queryVector), squaredMagnitude);
            }
            return new DenseVectorQuery.Bytes(queryVector, name());
        }

        private Query createExactKnnFloatQuery(float[] queryVector) {
            elementType.checkDimensions(dims, queryVector.length);
            elementType.checkVectorBounds(queryVector);
            if (similarity == VectorSimilarity.DOT_PRODUCT || similarity == VectorSimilarity.COSINE) {
                float squaredMagnitude = VectorUtil.dotProduct(queryVector, queryVector);
                elementType.checkVectorMagnitude(similarity, ElementType.errorFloatElementsAppender(queryVector), squaredMagnitude);
                if (similarity == VectorSimilarity.COSINE
                    && indexVersionCreated.onOrAfter(NORMALIZE_COSINE)
                    && isNotUnitVector(squaredMagnitude)) {
                    float length = (float) Math.sqrt(squaredMagnitude);
                    queryVector = Arrays.copyOf(queryVector, queryVector.length);
                    for (int i = 0; i < queryVector.length; i++) {
                        queryVector[i] /= length;
                    }
                }
            }
            return new DenseVectorQuery.Floats(queryVector, name());
        }

        public Query createKnnQuery(
            VectorData queryVector,
            int k,
            int numCands,
            Float oversample,
            Query filter,
            Float similarityThreshold,
            BitSetProducer parentFilter,
            FilterHeuristic heuristic,
            boolean hnswEarlyTermination
        ) {
            if (isIndexed() == false) {
                throw new IllegalArgumentException(
                    "to perform knn search on field [" + name() + "], its mapping must have [index] set to [true]"
                );
            }
            if (dims == null) {
                return new MatchNoDocsQuery("No data has been indexed for field [" + name() + "]");
            }
            KnnSearchStrategy knnSearchStrategy = heuristic.getKnnSearchStrategy();
            return switch (getElementType()) {
                case BYTE -> createKnnByteQuery(
                    queryVector.asByteVector(),
                    k,
                    numCands,
                    filter,
                    similarityThreshold,
                    parentFilter,
                    knnSearchStrategy,
                    hnswEarlyTermination
                );
                case FLOAT -> createKnnFloatQuery(
                    queryVector.asFloatVector(),
                    k,
                    numCands,
                    oversample,
                    filter,
                    similarityThreshold,
                    parentFilter,
                    knnSearchStrategy,
                    hnswEarlyTermination
                );
                case BIT -> createKnnBitQuery(
                    queryVector.asByteVector(),
                    k,
                    numCands,
                    filter,
                    similarityThreshold,
                    parentFilter,
                    knnSearchStrategy,
                    hnswEarlyTermination
                );
            };
        }

        private boolean needsRescore(Float rescoreOversample) {
            return rescoreOversample != null && rescoreOversample > 0 && isQuantized();
        }

        private boolean isQuantized() {
            return indexOptions != null && indexOptions.type != null && indexOptions.type.isQuantized();
        }

        private Query createKnnBitQuery(
            byte[] queryVector,
            int k,
            int numCands,
            Query filter,
            Float similarityThreshold,
            BitSetProducer parentFilter,
            KnnSearchStrategy searchStrategy,
            boolean hnswEarlyTermination
        ) {
            elementType.checkDimensions(dims, queryVector.length);
            Query knnQuery;
            if (indexOptions != null && indexOptions.isFlat()) {
                var exactKnnQuery = parentFilter != null
                    ? new DiversifyingParentBlockQuery(parentFilter, createExactKnnBitQuery(queryVector))
                    : createExactKnnBitQuery(queryVector);
                knnQuery = filter == null
                    ? exactKnnQuery
                    : new BooleanQuery.Builder().add(exactKnnQuery, BooleanClause.Occur.SHOULD)
                        .add(filter, BooleanClause.Occur.FILTER)
                        .build();
            } else {
                knnQuery = parentFilter != null
                    ? new ESDiversifyingChildrenByteKnnVectorQuery(name(), queryVector, filter, k, numCands, parentFilter, searchStrategy)
                    : new ESKnnByteVectorQuery(name(), queryVector, k, numCands, filter, searchStrategy);
                if (hnswEarlyTermination) {
                    knnQuery = maybeWrapPatience(knnQuery);
                }
            }
            if (similarityThreshold != null) {
                knnQuery = new VectorSimilarityQuery(
                    knnQuery,
                    similarityThreshold,
                    similarity.score(similarityThreshold, elementType, dims)
                );
            }
            return knnQuery;
        }

        private Query createKnnByteQuery(
            byte[] queryVector,
            int k,
            int numCands,
            Query filter,
            Float similarityThreshold,
            BitSetProducer parentFilter,
            KnnSearchStrategy searchStrategy,
            boolean hnswEarlyTermination
        ) {
            elementType.checkDimensions(dims, queryVector.length);

            if (similarity == VectorSimilarity.DOT_PRODUCT || similarity == VectorSimilarity.COSINE) {
                float squaredMagnitude = VectorUtil.dotProduct(queryVector, queryVector);
                elementType.checkVectorMagnitude(similarity, ElementType.errorByteElementsAppender(queryVector), squaredMagnitude);
            }
            Query knnQuery;
            if (indexOptions != null && indexOptions.isFlat()) {
                var exactKnnQuery = parentFilter != null
                    ? new DiversifyingParentBlockQuery(parentFilter, createExactKnnByteQuery(queryVector))
                    : createExactKnnByteQuery(queryVector);
                knnQuery = filter == null
                    ? exactKnnQuery
                    : new BooleanQuery.Builder().add(exactKnnQuery, BooleanClause.Occur.SHOULD)
                        .add(filter, BooleanClause.Occur.FILTER)
                        .build();
            } else {
                knnQuery = parentFilter != null
                    ? new ESDiversifyingChildrenByteKnnVectorQuery(name(), queryVector, filter, k, numCands, parentFilter, searchStrategy)
                    : new ESKnnByteVectorQuery(name(), queryVector, k, numCands, filter, searchStrategy);
                if (hnswEarlyTermination) {
                    knnQuery = maybeWrapPatience(knnQuery);
                }
            }
            if (similarityThreshold != null) {
                knnQuery = new VectorSimilarityQuery(
                    knnQuery,
                    similarityThreshold,
                    similarity.score(similarityThreshold, elementType, dims)
                );
            }
            return knnQuery;
        }

        private Query maybeWrapPatience(Query knnQuery) {
            Query finalQuery = knnQuery;
            if (knnQuery instanceof KnnByteVectorQuery knnByteVectorQuery && canApplyPatienceQuery()) {
                finalQuery = PatienceKnnVectorQuery.fromByteQuery(knnByteVectorQuery);
            } else if (knnQuery instanceof KnnFloatVectorQuery knnFloatVectorQuery && canApplyPatienceQuery()) {
                finalQuery = PatienceKnnVectorQuery.fromFloatQuery(knnFloatVectorQuery);
            }
            return finalQuery;
        }

        private boolean canApplyPatienceQuery() {
            return indexOptions instanceof HnswIndexOptions
                || indexOptions instanceof Int8HnswIndexOptions
                || indexOptions instanceof Int4HnswIndexOptions
                || indexOptions instanceof BBQHnswIndexOptions;
        }

        private Query createKnnFloatQuery(
            float[] queryVector,
            int k,
            int numCands,
            Float queryOversample,
            Query filter,
            Float similarityThreshold,
            BitSetProducer parentFilter,
            KnnSearchStrategy knnSearchStrategy,
            boolean hnswEarlyTermination
        ) {
            elementType.checkDimensions(dims, queryVector.length);
            elementType.checkVectorBounds(queryVector);
            if (similarity == VectorSimilarity.DOT_PRODUCT || similarity == VectorSimilarity.COSINE) {
                float squaredMagnitude = VectorUtil.dotProduct(queryVector, queryVector);
                elementType.checkVectorMagnitude(similarity, ElementType.errorFloatElementsAppender(queryVector), squaredMagnitude);
                if (similarity == VectorSimilarity.COSINE
                    && indexVersionCreated.onOrAfter(NORMALIZE_COSINE)
                    && isNotUnitVector(squaredMagnitude)) {
                    float length = (float) Math.sqrt(squaredMagnitude);
                    queryVector = Arrays.copyOf(queryVector, queryVector.length);
                    for (int i = 0; i < queryVector.length; i++) {
                        queryVector[i] /= length;
                    }
                }
            }

            int adjustedK = k;
            // By default utilize the quantized oversample is configured
            // allow the user provided at query time overwrite
            Float oversample = queryOversample;
            if (oversample == null
                && indexOptions instanceof QuantizedIndexOptions quantizedIndexOptions
                && quantizedIndexOptions.rescoreVector != null) {
                oversample = quantizedIndexOptions.rescoreVector.oversample;
            }
            boolean rescore = needsRescore(oversample);
            if (rescore) {
                // Will get k * oversample for rescoring, and get the top k
                adjustedK = Math.min((int) Math.ceil(k * oversample), OVERSAMPLE_LIMIT);
                numCands = Math.max(adjustedK, numCands);
            }
            Query knnQuery;
            if (indexOptions != null && indexOptions.isFlat()) {
                var exactKnnQuery = parentFilter != null
                    ? new DiversifyingParentBlockQuery(parentFilter, createExactKnnFloatQuery(queryVector))
                    : createExactKnnFloatQuery(queryVector);
                knnQuery = filter == null
                    ? exactKnnQuery
                    : new BooleanQuery.Builder().add(exactKnnQuery, BooleanClause.Occur.SHOULD)
                        .add(filter, BooleanClause.Occur.FILTER)
                        .build();
            } else if (indexOptions instanceof BBQIVFIndexOptions bbqIndexOptions) {
                knnQuery = parentFilter != null
                    ? new DiversifyingChildrenIVFKnnFloatVectorQuery(
                        name(),
                        queryVector,
                        adjustedK,
                        numCands,
                        filter,
                        parentFilter,
                        bbqIndexOptions.defaultNProbe
                    )
                    : new IVFKnnFloatVectorQuery(name(), queryVector, adjustedK, numCands, filter, bbqIndexOptions.defaultNProbe);
            } else {
                knnQuery = parentFilter != null
                    ? new ESDiversifyingChildrenFloatKnnVectorQuery(
                        name(),
                        queryVector,
                        filter,
                        adjustedK,
                        numCands,
                        parentFilter,
                        knnSearchStrategy
                    )
                    : new ESKnnFloatVectorQuery(name(), queryVector, adjustedK, numCands, filter, knnSearchStrategy);
                if (hnswEarlyTermination) {
                    knnQuery = maybeWrapPatience(knnQuery);
                }
            }
            if (rescore) {
                knnQuery = RescoreKnnVectorQuery.fromInnerQuery(
                    name(),
                    queryVector,
                    similarity.vectorSimilarityFunction(indexVersionCreated, ElementType.FLOAT),
                    k,
                    adjustedK,
                    knnQuery
                );
            }
            if (similarityThreshold != null) {
                knnQuery = new VectorSimilarityQuery(
                    knnQuery,
                    similarityThreshold,
                    similarity.score(similarityThreshold, elementType, dims)
                );
            }
            return knnQuery;
        }

        VectorSimilarity getSimilarity() {
            return similarity;
        }

        int getVectorDimensions() {
            return dims;
        }

        ElementType getElementType() {
            return elementType;
        }

        public DenseVectorIndexOptions getIndexOptions() {
            return indexOptions;
        }

        @Override
        public BlockLoader blockLoader(MappedFieldType.BlockLoaderContext blContext) {
            if (elementType != ElementType.FLOAT) {
                // Just float dense vector support for now
                return null;
            }

            if (dims == null) {
                // No data has been indexed yet
                return BlockLoader.CONSTANT_NULLS;
            }

            if (indexed) {
                return new BlockDocValuesReader.DenseVectorBlockLoader(name(), dims);
            }

            if (hasDocValues() && (blContext.fieldExtractPreference() != FieldExtractPreference.STORED || isSyntheticSource)) {
                return new BlockDocValuesReader.DenseVectorFromBinaryBlockLoader(name(), dims, indexVersionCreated);
            }

            BlockSourceReader.LeafIteratorLookup lookup = BlockSourceReader.lookupMatchingAll();
            return new BlockSourceReader.DenseVectorBlockLoader(sourceValueFetcher(blContext.sourcePaths(name())), lookup, dims);
        }

        private SourceValueFetcher sourceValueFetcher(Set<String> sourcePaths) {
            return new SourceValueFetcher(sourcePaths, null) {
                @Override
                protected Object parseSourceValue(Object value) {
                    if (value.equals("")) {
                        return null;
                    }
                    return NumberFieldMapper.NumberType.FLOAT.parse(value, false);
                }

                @Override
                public List<Object> fetchValues(Source source, int doc, List<Object> ignoredValues) {
                    List<Object> result = super.fetchValues(source, doc, ignoredValues);
                    assert result.size() == dims : "Unexpected number of dimensions; got " + result.size() + " but expected " + dims;
                    return result;
                }
            };
        }
    }

    private final DenseVectorIndexOptions indexOptions;
    private final IndexVersion indexCreatedVersion;
    private final boolean isSyntheticVector;

    private DenseVectorFieldMapper(
        String simpleName,
        MappedFieldType mappedFieldType,
        BuilderParams params,
        DenseVectorIndexOptions indexOptions,
        IndexVersion indexCreatedVersion,
        boolean isSyntheticVector
    ) {
        super(simpleName, mappedFieldType, params);
        this.indexOptions = indexOptions;
        this.indexCreatedVersion = indexCreatedVersion;
        this.isSyntheticVector = isSyntheticVector;
    }

    @Override
    public DenseVectorFieldType fieldType() {
        return (DenseVectorFieldType) super.fieldType();
    }

    @Override
    public boolean parsesArrayValue() {
        return true;
    }

    @Override
    public void parse(DocumentParserContext context) throws IOException {
        if (context.doc().getByKey(fieldType().name()) != null) {
            throw new IllegalArgumentException(
                "Field ["
                    + fullPath()
                    + "] of type ["
                    + typeName()
                    + "] doesn't support indexing multiple values for the same field in the same document"
            );
        }
        if (Token.VALUE_NULL == context.parser().currentToken()) {
            return;
        }
        if (fieldType().dims == null) {
            int dims = fieldType().elementType.parseDimensionCount(context);
            DenseVectorFieldMapper.Builder builder = (Builder) getMergeBuilder();
            builder.dimensions(dims);
            Mapper update = builder.build(context.createDynamicMapperBuilderContext());
            context.addDynamicMapper(update);
            return;
        }
        if (fieldType().indexed) {
            parseKnnVectorAndIndex(context);
        } else {
            parseBinaryDocValuesVectorAndIndex(context);
        }
    }

    private void parseKnnVectorAndIndex(DocumentParserContext context) throws IOException {
        fieldType().elementType.parseKnnVectorAndIndex(context, this);
    }

    private void parseBinaryDocValuesVectorAndIndex(DocumentParserContext context) throws IOException {
        // encode array of floats as array of integers and store into buf
        // this code is here and not in the VectorEncoderDecoder so not to create extra arrays
        int dims = fieldType().dims;
        ElementType elementType = fieldType().elementType;
        int numBytes = indexCreatedVersion.onOrAfter(MAGNITUDE_STORED_INDEX_VERSION)
            ? elementType.getNumBytes(dims) + MAGNITUDE_BYTES
            : elementType.getNumBytes(dims);

        ByteBuffer byteBuffer = elementType.createByteBuffer(indexCreatedVersion, numBytes);
        VectorData vectorData = elementType.parseKnnVector(context, dims, (i, b) -> {
            if (b) {
                checkDimensionMatches(i, context);
            } else {
                checkDimensionExceeded(i, context);
            }
        }, fieldType().similarity);
        vectorData.addToBuffer(byteBuffer);
        if (indexCreatedVersion.onOrAfter(MAGNITUDE_STORED_INDEX_VERSION)) {
            // encode vector magnitude at the end
            double dotProduct = elementType.computeSquaredMagnitude(vectorData);
            float vectorMagnitude = (float) Math.sqrt(dotProduct);
            byteBuffer.putFloat(vectorMagnitude);
        }
        Field field = new BinaryDocValuesField(fieldType().name(), new BytesRef(byteBuffer.array()));
        context.doc().addWithKey(fieldType().name(), field);
    }

    private void checkDimensionExceeded(int index, DocumentParserContext context) {
        if (index >= fieldType().dims) {
            throw new IllegalArgumentException(
                "The ["
                    + typeName()
                    + "] field ["
                    + fullPath()
                    + "] in doc ["
                    + context.documentDescription()
                    + "] has more dimensions "
                    + "than defined in the mapping ["
                    + fieldType().dims
                    + "]"
            );
        }
    }

    private void checkDimensionMatches(int index, DocumentParserContext context) {
        if (index != fieldType().dims) {
            throw new IllegalArgumentException(
                "The ["
                    + typeName()
                    + "] field ["
                    + fullPath()
                    + "] in doc ["
                    + context.documentDescription()
                    + "] has a different number of dimensions "
                    + "["
                    + index
                    + "] than defined in the mapping ["
                    + fieldType().dims
                    + "]"
            );
        }
    }

    @Override
    protected void parseCreateField(DocumentParserContext context) {
        throw new AssertionError("parse is implemented directly");
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public FieldMapper.Builder getMergeBuilder() {
        return new Builder(leafName(), indexCreatedVersion, isSyntheticVector).init(this);
    }

    private static DenseVectorIndexOptions parseIndexOptions(String fieldName, Object propNode, IndexVersion indexVersion) {
        @SuppressWarnings("unchecked")
        Map<String, ?> indexOptionsMap = (Map<String, ?>) propNode;
        Object typeNode = indexOptionsMap.remove("type");
        if (typeNode == null) {
            throw new MapperParsingException("[index_options] requires field [type] to be configured");
        }
        String type = XContentMapValues.nodeStringValue(typeNode);
        Optional<VectorIndexType> vectorIndexType = VectorIndexType.fromString(type);
        if (vectorIndexType.isEmpty()) {
            throw new MapperParsingException("Unknown vector index options type [" + type + "] for field [" + fieldName + "]");
        }
        VectorIndexType parsedType = vectorIndexType.get();
        return parsedType.parseIndexOptions(fieldName, indexOptionsMap, indexVersion);
    }

    /**
     * @return the custom kNN vectors format that is configured for this field or
     * {@code null} if the default format should be used.
     */
    public KnnVectorsFormat getKnnVectorsFormatForField(KnnVectorsFormat defaultFormat) {
        final KnnVectorsFormat format;
        if (indexOptions == null) {
            format = fieldType().elementType == ElementType.BIT ? new ES815HnswBitVectorsFormat() : defaultFormat;
        } else {
            format = indexOptions.getVectorsFormat(fieldType().elementType);
        }
        // It's legal to reuse the same format name as this is the same on-disk format.
        return new KnnVectorsFormat(format.getName()) {
            @Override
            public KnnVectorsWriter fieldsWriter(SegmentWriteState state) throws IOException {
                return format.fieldsWriter(state);
            }

            @Override
            public KnnVectorsReader fieldsReader(SegmentReadState state) throws IOException {
                return format.fieldsReader(state);
            }

            @Override
            public int getMaxDimensions(String fieldName) {
                return MAX_DIMS_COUNT;
            }

            @Override
            public String toString() {
                return format.toString();
            }
        };
    }

    @Override
    public SourceLoader.SyntheticVectorsLoader syntheticVectorsLoader() {
        if (isSyntheticVector) {
            var syntheticField = new IndexedSyntheticFieldLoader(indexCreatedVersion, fieldType().similarity);
            return new SyntheticVectorsPatchFieldLoader(syntheticField, syntheticField::copyVectorAsList);
        }
        return null;
    }

    @Override
    protected SyntheticSourceSupport syntheticSourceSupport() {
        return new SyntheticSourceSupport.Native(
            () -> fieldType().indexed
                ? new IndexedSyntheticFieldLoader(indexCreatedVersion, fieldType().similarity)
                : new DocValuesSyntheticFieldLoader(indexCreatedVersion)
        );
    }

    private class IndexedSyntheticFieldLoader extends SourceLoader.DocValuesBasedSyntheticFieldLoader {
        private FloatVectorValues floatValues;
        private ByteVectorValues byteValues;
        private NumericDocValues magnitudeReader;

        private boolean hasValue;
        private boolean hasMagnitude;
        private int ord;

        private final IndexVersion indexCreatedVersion;
        private final VectorSimilarity vectorSimilarity;

        private IndexedSyntheticFieldLoader(IndexVersion indexCreatedVersion, VectorSimilarity vectorSimilarity) {
            this.indexCreatedVersion = indexCreatedVersion;
            this.vectorSimilarity = vectorSimilarity;
        }

        @Override
        public DocValuesLoader docValuesLoader(LeafReader reader, int[] docIdsInLeaf) throws IOException {
            floatValues = reader.getFloatVectorValues(fullPath());
            if (floatValues != null) {
                if (shouldNormalize()) {
                    magnitudeReader = reader.getNumericDocValues(fullPath() + COSINE_MAGNITUDE_FIELD_SUFFIX);
                }
                return createLoader(floatValues.iterator(), true);
            }

            byteValues = reader.getByteVectorValues(fullPath());
            if (byteValues != null) {
                return createLoader(byteValues.iterator(), false);
            }

            return null;
        }

        private boolean shouldNormalize() {
            return indexCreatedVersion.onOrAfter(NORMALIZE_COSINE) && VectorSimilarity.COSINE.equals(vectorSimilarity);
        }

        private DocValuesLoader createLoader(KnnVectorValues.DocIndexIterator iterator, boolean checkMagnitude) {
            return docId -> {
                if (iterator.docID() > docId) {
                    return hasValue = false;
                }
                if (iterator.docID() == docId || iterator.advance(docId) == docId) {
                    ord = iterator.index();
                    hasValue = true;
                    hasMagnitude = checkMagnitude && magnitudeReader != null && magnitudeReader.advanceExact(docId);
                } else {
                    hasValue = false;
                }
                return hasValue;
            };
        }

        @Override
        public boolean hasValue() {
            return hasValue;
        }

        @Override
        public void write(XContentBuilder b) throws IOException {
            if (false == hasValue) {
                return;
            }
            float magnitude = hasMagnitude ? Float.intBitsToFloat((int) magnitudeReader.longValue()) : Float.NaN;
            b.startArray(leafName());
            if (floatValues != null) {
                for (float v : floatValues.vectorValue(ord)) {
                    b.value(hasMagnitude ? v * magnitude : v);
                }
            } else if (byteValues != null) {
                for (byte v : byteValues.vectorValue(ord)) {
                    b.value(v);
                }
            }
            b.endArray();
        }

        /**
         * Returns a deep-copied vector for the current document, either as a list of floats
         * (with optional cosine normalization) or a list of bytes.
         *
         * @throws IOException if reading fails
         */
        private List<?> copyVectorAsList() throws IOException {
            assert hasValue : "vector is null for ord=" + ord;
            if (floatValues != null) {
                float[] raw = floatValues.vectorValue(ord);
                List<Float> copyList = new ArrayList<>(raw.length);

                if (hasMagnitude) {
                    float mag = Float.intBitsToFloat((int) magnitudeReader.longValue());
                    for (int i = 0; i < raw.length; i++) {
                        copyList.add(raw[i] * mag);
                    }
                } else {
                    for (int i = 0; i < raw.length; i++) {
                        copyList.add(raw[i]);
                    }
                }
                return copyList;
            } else if (byteValues != null) {
                byte[] raw = byteValues.vectorValue(ord);
                List<Byte> copyList = new ArrayList<>(raw.length);
                for (int i = 0; i < raw.length; i++) {
                    copyList.add(raw[i]);
                }
                return copyList;
            }

            throw new IllegalStateException("No vector values available to copy.");
        }

        @Override
        public String fieldName() {
            return fullPath();
        }
    }

    private class DocValuesSyntheticFieldLoader extends SourceLoader.DocValuesBasedSyntheticFieldLoader {
        private BinaryDocValues values;
        private boolean hasValue;
        private final IndexVersion indexCreatedVersion;

        private DocValuesSyntheticFieldLoader(IndexVersion indexCreatedVersion) {
            this.indexCreatedVersion = indexCreatedVersion;
        }

        @Override
        public DocValuesLoader docValuesLoader(LeafReader leafReader, int[] docIdsInLeaf) throws IOException {
            values = leafReader.getBinaryDocValues(fullPath());
            if (values == null) {
                return null;
            }
            return docId -> {
                if (values.docID() > docId) {
                    return hasValue = false;
                }
                if (values.docID() == docId) {
                    return hasValue = true;
                }
                hasValue = docId == values.advance(docId);
                return hasValue;
            };
        }

        @Override
        public boolean hasValue() {
            return hasValue;
        }

        @Override
        public void write(XContentBuilder b) throws IOException {
            if (false == hasValue) {
                return;
            }
            b.startArray(leafName());
            BytesRef ref = values.binaryValue();
            ByteBuffer byteBuffer = ByteBuffer.wrap(ref.bytes, ref.offset, ref.length);
            if (indexCreatedVersion.onOrAfter(LITTLE_ENDIAN_FLOAT_STORED_INDEX_VERSION)) {
                byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
            }
            int dims = fieldType().elementType == ElementType.BIT ? fieldType().dims / Byte.SIZE : fieldType().dims;
            for (int dim = 0; dim < dims; dim++) {
                fieldType().elementType.readAndWriteValue(byteBuffer, b);
            }
            b.endArray();
        }

        @Override
        public String fieldName() {
            return fullPath();
        }
    }

    /**
     * Interface for a function that takes a int and boolean
     */
    @FunctionalInterface
    public interface IntBooleanConsumer {
        void accept(int value, boolean isComplete);
    }
}
