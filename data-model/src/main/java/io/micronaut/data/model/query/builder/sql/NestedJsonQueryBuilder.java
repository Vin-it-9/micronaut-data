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
package io.micronaut.data.model.query.builder.sql;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.data.annotation.EmbeddedId;
import io.micronaut.data.annotation.Join;
import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.data.annotation.OrderBy;
import io.micronaut.data.annotation.QueryResult;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.annotation.sql.JoinColumn;
import io.micronaut.data.exceptions.MappingException;
import io.micronaut.data.model.Association;
import io.micronaut.data.model.DataType;
import io.micronaut.data.model.Embedded;
import io.micronaut.data.model.JsonDataType;
import io.micronaut.data.model.PersistentEntity;
import io.micronaut.data.model.PersistentEntityUtils;
import io.micronaut.data.model.PersistentProperty;
import io.micronaut.data.model.naming.NamingStrategy;
import io.micronaut.data.model.query.JoinPath;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds nested SQL/JSON projections for repositories annotated with
 * {@link QueryResult} of type {@link QueryResult.Type#JSON}.
 *
 * Supported dialects: {@link Dialect#POSTGRES} and {@link Dialect#ORACLE}.
 *
 * @author Vinit Shinde
 * @since 5.3.0
 */
@Internal
public class NestedJsonQueryBuilder {

    private static final String JSON_PROPERTY_ANNOTATION = "com.fasterxml.jackson.annotation.JsonProperty";
    private static final String JSON_IGNORE_ANNOTATION = "com.fasterxml.jackson.annotation.JsonIgnore";
    private static final String SERDE_CONFIG_ANNOTATION = "io.micronaut.serde.config.annotation.SerdeConfig";
    private static final String SERDE_IGNORED_ANNOTATION = "io.micronaut.serde.config.annotation.SerdeConfig$Ignored";

    private final AbstractSqlLikeQueryBuilder sqlQueryBuilder;
    private final Dialect dialect;

    public NestedJsonQueryBuilder(AbstractSqlLikeQueryBuilder sqlQueryBuilder) {
        this.sqlQueryBuilder = sqlQueryBuilder;
        this.dialect = sqlQueryBuilder.getDialect();
    }

    /**
     * Checks whether the dialect supports nested SQL/JSON generation.
     *
     * @param dialect the dialect to check
     * @return true if supported
     */
    public static boolean isNestedJsonSupported(Dialect dialect) {
        return dialect == Dialect.POSTGRES || dialect == Dialect.ORACLE;
    }

    /**
     * Checks if the annotation metadata requests a JSON query result.
     *
     * @param annotationMetadata the method/repository annotation metadata
     * @return true if @QueryResult(type = JSON) is present
     */
    public static boolean isNestedJsonQueryResult(AnnotationMetadata annotationMetadata) {
        if (annotationMetadata == null) {
            return false;
        }
        AnnotationValue<QueryResult> queryResultAnn = annotationMetadata.getAnnotation(QueryResult.class);
        return queryResultAnn != null && queryResultAnn.enumValue("type", QueryResult.Type.class)
            .orElse(QueryResult.Type.TABULAR) == QueryResult.Type.JSON;
    }

    /**
     * Builds the complete nested SQL/JSON projection expression.
     *
     * @param entity             the root entity
     * @param rootAlias          the root table alias
     * @param joinPaths          the selected join paths
     * @param annotationMetadata the query annotation metadata
     * @return the SQL projection string (e.g. "json_build_object(...) AS \"DATA\"")
     */
    public String buildNestedJsonProjection(PersistentEntity entity,
                                           String rootAlias,
                                           Collection<JoinPath> joinPaths,
                                           AnnotationMetadata annotationMetadata) {
        if (!isNestedJsonSupported(dialect)) {
            throw new IllegalArgumentException("Nested JSON query result (@QueryResult(type = JSON)) is not supported for dialect ["
                + dialect + "]. Currently supported dialects are PostgreSQL and Oracle.");
        }

        String targetColumn = annotationMetadata.stringValue(QueryResult.class, "column")
            .orElse(QueryResult.DEFAULT_COLUMN);

        JsonDataType jsonDataType = annotationMetadata.enumValue(QueryResult.class, "jsonDataType", JsonDataType.class)
            .orElse(JsonDataType.DEFAULT);

        StringBuilder sb = new StringBuilder();

        Map<String, List<JoinPath>> pathsByRootPrefix = groupJoinPathsByPrefix(joinPaths, "");

        Set<PersistentEntity> visitedEntities = new HashSet<>();
        visitedEntities.add(entity);

        if (dialect == Dialect.POSTGRES) {
            sb.append("json_build_object(");
            appendEntityPropertiesPostgres(sb, entity, rootAlias, pathsByRootPrefix, visitedEntities);
            sb.append(")");
        } else {
            // Dialect.ORACLE
            String rootReturningClause = getOracleRootReturningClause(jsonDataType);
            sb.append("JSON_OBJECT(");
            appendEntityPropertiesOracle(sb, entity, rootAlias, pathsByRootPrefix, visitedEntities, false);
            sb.append(" ").append(rootReturningClause).append(")");
        }

        sb.append(AbstractSqlLikeQueryBuilder.AS_CLAUSE);
        boolean escape = sqlQueryBuilder.shouldEscape(entity);
        if (escape) {
            sb.append(sqlQueryBuilder.quote(targetColumn));
        } else {
            sb.append(targetColumn);
        }

        return sb.toString();
    }

    private String getOracleRootReturningClause(JsonDataType jsonDataType) {
        return switch (jsonDataType) {
            case STRING -> "RETURNING CLOB";
            case BLOB -> "RETURNING BLOB";
            default -> "RETURNING JSON";
        };
    }

    /**
     * Appends property pairs for PostgreSQL: 'propName', value, ...
     */
    private void appendEntityPropertiesPostgres(StringBuilder sb,
                                                PersistentEntity entity,
                                                String alias,
                                                Map<String, List<JoinPath>> nestedPaths,
                                                Set<PersistentEntity> visitedEntities) {
        boolean first = true;
        NamingStrategy namingStrategy = sqlQueryBuilder.getNamingStrategy(entity);
        boolean escape = sqlQueryBuilder.shouldEscape(entity);

        // 1. Identity properties
        List<PersistentProperty> identities = entity.getIdentityProperties();
        for (PersistentProperty identity : identities) {
            if (isIgnored(identity)) {
                continue;
            }
            if (!first) {
                sb.append(", ");
            }
            first = false;
            String jsonPropName = getJsonPropertyName(identity);
            sb.append("'").append(escapeJsonFieldName(jsonPropName)).append("', ");

            if (identity instanceof Embedded embedded) {
                appendEmbeddedPostgres(sb, embedded, alias, namingStrategy, escape);
            } else {
                appendColumnRef(sb, alias, identity, namingStrategy, escape);
            }
        }

        // 2. Persistent non-identity properties
        for (PersistentProperty prop : entity.getPersistentProperties()) {
            if (isIgnored(prop) || isIdentityProperty(identities, prop)) {
                continue;
            }

            if (prop instanceof Association association) {
                if (association.isEmbedded()) {
                    if (!first) {
                        sb.append(", ");
                    }
                    first = false;
                    appendEmbeddedAssociationPostgres(sb, (Embedded) association, alias, namingStrategy, escape);
                } else {
                    List<JoinPath> pathsForAssoc = nestedPaths.get(association.getName());
                    if (pathsForAssoc != null) {
                        if (!first) {
                            sb.append(", ");
                        }
                        first = false;
                        String jsonPropName = getJsonPropertyName(association);
                        sb.append("'").append(escapeJsonFieldName(jsonPropName)).append("', ");
                        appendAssociationPostgres(sb, entity, alias, association, pathsForAssoc, visitedEntities);
                    }
                }
            } else {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                String jsonPropName = getJsonPropertyName(prop);
                sb.append("'").append(escapeJsonFieldName(jsonPropName)).append("', ");
                appendColumnRef(sb, alias, prop, namingStrategy, escape);
            }
        }
    }

    /**
     * Appends property pairs for Oracle: 'propName' VALUE value, ...
     */
    private void appendEntityPropertiesOracle(StringBuilder sb,
                                              PersistentEntity entity,
                                              String alias,
                                              Map<String, List<JoinPath>> nestedPaths,
                                              Set<PersistentEntity> visitedEntities,
                                              boolean isNested) {
        boolean first = true;
        NamingStrategy namingStrategy = sqlQueryBuilder.getNamingStrategy(entity);
        boolean escape = sqlQueryBuilder.shouldEscape(entity);

        // 1. Identity properties
        List<PersistentProperty> identities = entity.getIdentityProperties();
        for (PersistentProperty identity : identities) {
            if (isIgnored(identity)) {
                continue;
            }
            if (!first) {
                sb.append(", ");
            }
            first = false;
            String jsonPropName = getJsonPropertyName(identity);
            sb.append("'").append(escapeJsonFieldName(jsonPropName)).append("' VALUE ");

            if (identity instanceof Embedded embedded) {
                appendEmbeddedOracle(sb, embedded, alias, namingStrategy, escape);
            } else {
                appendColumnRef(sb, alias, identity, namingStrategy, escape);
            }
        }

        // 2. Persistent non-identity properties
        for (PersistentProperty prop : entity.getPersistentProperties()) {
            if (isIgnored(prop) || isIdentityProperty(identities, prop)) {
                continue;
            }

            if (prop instanceof Association association) {
                if (association.isEmbedded()) {
                    if (!first) {
                        sb.append(", ");
                    }
                    first = false;
                    appendEmbeddedAssociationOracle(sb, (Embedded) association, alias, namingStrategy, escape);
                } else {
                    List<JoinPath> pathsForAssoc = nestedPaths.get(association.getName());
                    if (pathsForAssoc != null) {
                        if (!first) {
                            sb.append(", ");
                        }
                        first = false;
                        String jsonPropName = getJsonPropertyName(association);
                        sb.append("'").append(escapeJsonFieldName(jsonPropName)).append("' VALUE ");
                        appendAssociationOracle(sb, entity, alias, association, pathsForAssoc, visitedEntities);
                    }
                }
            } else {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                String jsonPropName = getJsonPropertyName(prop);
                sb.append("'").append(escapeJsonFieldName(jsonPropName)).append("' VALUE ");
                appendColumnRef(sb, alias, prop, namingStrategy, escape);
            }
        }
    }

    private void appendColumnRef(StringBuilder sb,
                                 String alias,
                                 PersistentProperty prop,
                                 NamingStrategy namingStrategy,
                                 boolean escape) {
        String colName = sqlQueryBuilder.getMappedName(namingStrategy, Collections.emptyList(), prop);
        String quotedCol = escape ? sqlQueryBuilder.quote(colName) : colName;

        if (sqlQueryBuilder.isJsonOrWktGeometry(prop)) {
            if (dialect == Dialect.POSTGRES) {
                sb.append("ST_AsGeoJSON(").append(alias).append(".").append(quotedCol).append(")::json");
            } else {
                sb.append("SDO_UTIL.TO_GEOJSON(").append(alias).append(".").append(quotedCol).append(")");
            }
        } else if (prop.getDataType() == DataType.JSON && dialect == Dialect.POSTGRES) {
            sb.append("to_json(").append(alias).append(".").append(quotedCol).append("::json)");
        } else {
            sb.append(alias).append(".").append(quotedCol);
        }
    }

    private void appendEmbeddedPostgres(StringBuilder sb,
                                        Embedded embedded,
                                        String alias,
                                        NamingStrategy namingStrategy,
                                        boolean escape) {
        PersistentEntity associatedEntity = embedded.getAssociatedEntity();
        boolean unwrapped = embedded.getAnnotationMetadata().hasAnnotation(JsonUnwrapped.class);
        if (!unwrapped) {
            sb.append("json_build_object(");
        }
        boolean first = true;
        for (PersistentProperty prop : associatedEntity.getPersistentProperties()) {
            if (isIgnored(prop)) {
                continue;
            }
            if (!first) {
                sb.append(", ");
            }
            first = false;
            String jsonPropName = getJsonPropertyName(prop);
            sb.append("'").append(escapeJsonFieldName(jsonPropName)).append("', ");
            String colName = sqlQueryBuilder.getMappedName(namingStrategy, List.of(embedded), prop);
            String quotedCol = escape ? sqlQueryBuilder.quote(colName) : colName;
            sb.append(alias).append(".").append(quotedCol);
        }
        if (!unwrapped) {
            sb.append(")");
        }
    }

    private void appendEmbeddedOracle(StringBuilder sb,
                                      Embedded embedded,
                                      String alias,
                                      NamingStrategy namingStrategy,
                                      boolean escape) {
        PersistentEntity associatedEntity = embedded.getAssociatedEntity();
        boolean unwrapped = embedded.getAnnotationMetadata().hasAnnotation(JsonUnwrapped.class);
        if (!unwrapped) {
            sb.append("JSON_OBJECT(");
        }
        boolean first = true;
        for (PersistentProperty prop : associatedEntity.getPersistentProperties()) {
            if (isIgnored(prop)) {
                continue;
            }
            if (!first) {
                sb.append(", ");
            }
            first = false;
            String jsonPropName = getJsonPropertyName(prop);
            sb.append("'").append(escapeJsonFieldName(jsonPropName)).append("' VALUE ");
            String colName = sqlQueryBuilder.getMappedName(namingStrategy, List.of(embedded), prop);
            String quotedCol = escape ? sqlQueryBuilder.quote(colName) : colName;
            sb.append(alias).append(".").append(quotedCol);
        }
        if (!unwrapped) {
            sb.append(" RETURNING JSON)");
        }
    }

    private void appendEmbeddedAssociationPostgres(StringBuilder sb,
                                                   Embedded embedded,
                                                   String alias,
                                                   NamingStrategy namingStrategy,
                                                   boolean escape) {
        String jsonPropName = getJsonPropertyName(embedded);
        boolean unwrapped = embedded.getAnnotationMetadata().hasAnnotation(JsonUnwrapped.class);
        if (!unwrapped) {
            sb.append("'").append(escapeJsonFieldName(jsonPropName)).append("', ");
        }
        appendEmbeddedPostgres(sb, embedded, alias, namingStrategy, escape);
    }

    private void appendEmbeddedAssociationOracle(StringBuilder sb,
                                                 Embedded embedded,
                                                 String alias,
                                                 NamingStrategy namingStrategy,
                                                 boolean escape) {
        String jsonPropName = getJsonPropertyName(embedded);
        boolean unwrapped = embedded.getAnnotationMetadata().hasAnnotation(JsonUnwrapped.class);
        if (!unwrapped) {
            sb.append("'").append(escapeJsonFieldName(jsonPropName)).append("' VALUE ");
        }
        appendEmbeddedOracle(sb, embedded, alias, namingStrategy, escape);
    }

    /**
     * Appends a correlated subquery for an association in PostgreSQL.
     */
    private void appendAssociationPostgres(StringBuilder sb,
                                          PersistentEntity ownerEntity,
                                          String ownerAlias,
                                          Association association,
                                          List<JoinPath> pathsForAssoc,
                                          Set<PersistentEntity> visitedEntities) {
        PersistentEntity targetEntity = association.getAssociatedEntity();
        if (visitedEntities.contains(targetEntity)) {
            throw new MappingException("Cyclic association detected for path [" + association.getName()
                + "] of entity [" + ownerEntity.getName() + "]. Nested JSON projection requires a finite graph.");
        }

        Set<PersistentEntity> nextVisited = new HashSet<>(visitedEntities);
        nextVisited.add(targetEntity);

        String childAlias = resolveChildAlias(association, pathsForAssoc);
        boolean isToMany = association.getKind() == Relation.Kind.ONE_TO_MANY || association.getKind() == Relation.Kind.MANY_TO_MANY;

        Map<String, List<JoinPath>> nextNestedPaths = stripPrefixFromJoinPaths(pathsForAssoc, association.getName());

        sb.append("(SELECT ");
        if (isToMany) {
            sb.append("json_agg(json_build_object(");
        } else {
            sb.append("json_build_object(");
        }

        appendEntityPropertiesPostgres(sb, targetEntity, childAlias, nextNestedPaths, nextVisited);

        if (isToMany) {
            sb.append(")");
            String orderBy = resolveOrderByClause(association, targetEntity, childAlias);
            if (StringUtils.isNotEmpty(orderBy)) {
                sb.append(" ").append(orderBy);
            }
            sb.append(")");
        } else {
            sb.append(")");
        }

        sb.append(AbstractSqlLikeQueryBuilder.FROM_CLAUSE)
            .append(sqlQueryBuilder.getTableName(targetEntity))
            .append(" ")
            .append(childAlias);

        appendJoinTableAndWhereCondition(sb, ownerEntity, ownerAlias, association, targetEntity, childAlias);

        sb.append(")");
    }

    /**
     * Appends a correlated subquery for an association in Oracle.
     */
    private void appendAssociationOracle(StringBuilder sb,
                                         PersistentEntity ownerEntity,
                                         String ownerAlias,
                                         Association association,
                                         List<JoinPath> pathsForAssoc,
                                         Set<PersistentEntity> visitedEntities) {
        PersistentEntity targetEntity = association.getAssociatedEntity();
        if (visitedEntities.contains(targetEntity)) {
            throw new MappingException("Cyclic association detected for path [" + association.getName()
                + "] of entity [" + ownerEntity.getName() + "]. Nested JSON projection requires a finite graph.");
        }

        Set<PersistentEntity> nextVisited = new HashSet<>(visitedEntities);
        nextVisited.add(targetEntity);

        String childAlias = resolveChildAlias(association, pathsForAssoc);
        boolean isToMany = association.getKind() == Relation.Kind.ONE_TO_MANY || association.getKind() == Relation.Kind.MANY_TO_MANY;

        Map<String, List<JoinPath>> nextNestedPaths = stripPrefixFromJoinPaths(pathsForAssoc, association.getName());

        sb.append("(SELECT ");
        if (isToMany) {
            sb.append("JSON_ARRAYAGG(JSON_OBJECT(");
        } else {
            sb.append("JSON_OBJECT(");
        }

        appendEntityPropertiesOracle(sb, targetEntity, childAlias, nextNestedPaths, nextVisited, true);

        if (isToMany) {
            sb.append(" RETURNING JSON)");
            String orderBy = resolveOrderByClause(association, targetEntity, childAlias);
            if (StringUtils.isNotEmpty(orderBy)) {
                sb.append(" ").append(orderBy);
            }
            sb.append(" RETURNING JSON)");
        } else {
            sb.append(" RETURNING JSON)");
        }

        sb.append(AbstractSqlLikeQueryBuilder.FROM_CLAUSE)
            .append(sqlQueryBuilder.getTableName(targetEntity))
            .append(" ")
            .append(childAlias);

        appendJoinTableAndWhereCondition(sb, ownerEntity, ownerAlias, association, targetEntity, childAlias);

        sb.append(")");
    }

    private void appendJoinTableAndWhereCondition(StringBuilder sb,
                                                  PersistentEntity ownerEntity,
                                                  String ownerAlias,
                                                  Association association,
                                                  PersistentEntity targetEntity,
                                                  String childAlias) {
        boolean escapeOwner = sqlQueryBuilder.shouldEscape(ownerEntity);
        boolean escapeTarget = sqlQueryBuilder.shouldEscape(targetEntity);
        NamingStrategy ownerNamingStrategy = sqlQueryBuilder.getNamingStrategy(ownerEntity);
        NamingStrategy targetNamingStrategy = sqlQueryBuilder.getNamingStrategy(targetEntity);

        AnnotationMetadata annotationMetadata = association.getAnnotationMetadata();
        boolean hasJoinTable = association.getKind() == Relation.Kind.MANY_TO_MANY
            || annotationMetadata.hasAnnotation(SqlQueryBuilderUtils.ANN_JOIN_TABLE);

        if (hasJoinTable) {
            String joinTableName = annotationMetadata.stringValue(SqlQueryBuilderUtils.ANN_JOIN_TABLE, "name")
                .orElseGet(() -> ownerNamingStrategy.mappedName(association));
            String jtAlias = childAlias + "_jt_";
            String quotedJt = escapeOwner ? sqlQueryBuilder.quote(joinTableName, true) : joinTableName;

            List<String> leftJoinCols = SqlQueryBuilderUtils.resolveJoinTableJoinColumns(annotationMetadata, true, ownerEntity, ownerNamingStrategy);
            List<String> rightJoinCols = SqlQueryBuilderUtils.resolveJoinTableJoinColumns(annotationMetadata, false, targetEntity, targetNamingStrategy);

            PersistentProperty ownerId = ownerEntity.getIdentity();
            PersistentProperty targetId = targetEntity.getIdentity();

            String ownerIdCol = escapeOwner ? sqlQueryBuilder.quote(ownerId.getPersistedName()) : ownerId.getPersistedName();
            String targetIdCol = escapeTarget ? sqlQueryBuilder.quote(targetId.getPersistedName()) : targetId.getPersistedName();

            String jtRightCol = escapeTarget ? sqlQueryBuilder.quote(rightJoinCols.get(0)) : rightJoinCols.get(0);
            String jtLeftCol = escapeOwner ? sqlQueryBuilder.quote(leftJoinCols.get(0)) : leftJoinCols.get(0);

            sb.append(" JOIN ").append(quotedJt).append(" ").append(jtAlias)
                .append(" ON ").append(jtAlias).append(".").append(jtRightCol)
                .append(" = ").append(childAlias).append(".").append(targetIdCol)
                .append(AbstractSqlLikeQueryBuilder.WHERE_CLAUSE)
                .append(jtAlias).append(".").append(jtLeftCol)
                .append(" = ").append(ownerAlias).append(".").append(ownerIdCol);
            return;
        }

        // Single table foreign key link
        String mappedBy = annotationMetadata.stringValue(Relation.class, "mappedBy").orElse(null);

        if (StringUtils.isNotEmpty(mappedBy)) {
            // Foreign key is in targetEntity (child) pointing to ownerEntity (parent)
            PersistentProperty mappedByProp = targetEntity.getPropertyByName(mappedBy);
            String childFkCol = null;
            if (mappedByProp != null) {
                childFkCol = mappedByProp.getAnnotationMetadata().stringValue(JoinColumn.class, "name")
                    .orElseGet(mappedByProp::getPersistedName);
            }
            if (childFkCol == null) {
                childFkCol = ownerNamingStrategy.mappedName(ownerEntity.getSimpleName()) + "_id";
            }
            PersistentProperty ownerId = ownerEntity.getIdentity();
            String ownerIdCol = ownerId != null ? ownerId.getPersistedName() : "id";

            String quotedChildFk = escapeTarget ? sqlQueryBuilder.quote(childFkCol) : childFkCol;
            String quotedOwnerId = escapeOwner ? sqlQueryBuilder.quote(ownerIdCol) : ownerIdCol;

            sb.append(AbstractSqlLikeQueryBuilder.WHERE_CLAUSE)
                .append(childAlias).append(".").append(quotedChildFk)
                .append(" = ")
                .append(ownerAlias).append(".").append(quotedOwnerId);
        } else if (association.getKind() == Relation.Kind.MANY_TO_ONE || (association.getKind() == Relation.Kind.ONE_TO_ONE && !association.isForeignKey())) {
            // Foreign key is in ownerEntity (parent) pointing to targetEntity (child)
            String ownerFkCol = association.getAnnotationMetadata().stringValue(JoinColumn.class, "name")
                .orElseGet(association::getPersistedName);
            PersistentProperty targetId = targetEntity.getIdentity();
            String targetIdCol = targetId != null ? targetId.getPersistedName() : "id";

            String quotedOwnerFk = escapeOwner ? sqlQueryBuilder.quote(ownerFkCol) : ownerFkCol;
            String quotedTargetId = escapeTarget ? sqlQueryBuilder.quote(targetIdCol) : targetIdCol;

            sb.append(AbstractSqlLikeQueryBuilder.WHERE_CLAUSE)
                .append(childAlias).append(".").append(quotedTargetId)
                .append(" = ")
                .append(ownerAlias).append(".").append(quotedOwnerFk);
        } else {
            // OneToMany without mappedBy
            String childFkCol = association.getAnnotationMetadata().stringValue(JoinColumn.class, "name")
                .orElseGet(() -> ownerNamingStrategy.mappedName(ownerEntity.getSimpleName()) + "_id");
            PersistentProperty ownerId = ownerEntity.getIdentity();
            String ownerIdCol = ownerId != null ? ownerId.getPersistedName() : "id";

            String quotedChildFk = escapeTarget ? sqlQueryBuilder.quote(childFkCol) : childFkCol;
            String quotedOwnerId = escapeOwner ? sqlQueryBuilder.quote(ownerIdCol) : ownerIdCol;

            sb.append(AbstractSqlLikeQueryBuilder.WHERE_CLAUSE)
                .append(childAlias).append(".").append(quotedChildFk)
                .append(" = ")
                .append(ownerAlias).append(".").append(quotedOwnerId);
        }
    }

    private String resolveChildAlias(Association association, List<JoinPath> pathsForAssoc) {
        for (JoinPath jp : pathsForAssoc) {
            if (jp.getPath().equals(association.getName()) && jp.getAlias().isPresent()) {
                return sqlQueryBuilder.normalizeAlias(jp.getAlias().get());
            }
        }
        return association.getAliasName();
    }

    private String resolveOrderByClause(Association association, PersistentEntity targetEntity, String childAlias) {
        AnnotationMetadata metadata = association.getAnnotationMetadata();
        String orderByValue = metadata.stringValue(OrderBy.class).orElse(null);
        if (StringUtils.isNotEmpty(orderByValue)) {
            boolean escape = sqlQueryBuilder.shouldEscape(targetEntity);
            return "ORDER BY " + childAlias + "." + (escape ? sqlQueryBuilder.quote(orderByValue) : orderByValue);
        }

        PersistentProperty ordProp = targetEntity.getPropertyByName("ord");
        if (ordProp != null) {
            boolean escape = sqlQueryBuilder.shouldEscape(targetEntity);
            return "ORDER BY " + childAlias + "." + (escape ? sqlQueryBuilder.quote(ordProp.getPersistedName()) : ordProp.getPersistedName());
        }

        return null;
    }

    private String getJsonPropertyName(PersistentProperty property) {
        AnnotationMetadata metadata = property.getAnnotationMetadata();
        return metadata.stringValue(SERDE_CONFIG_ANNOTATION, "property")
            .orElseGet(() -> metadata.stringValue(JSON_PROPERTY_ANNOTATION)
                .orElseGet(property::getName));
    }

    private boolean isIgnored(PersistentProperty property) {
        AnnotationMetadata metadata = property.getAnnotationMetadata();
        return metadata.hasAnnotation(JSON_IGNORE_ANNOTATION)
            || metadata.hasAnnotation(SERDE_IGNORED_ANNOTATION)
            || metadata.booleanValue(SERDE_CONFIG_ANNOTATION, "ignored").orElse(false);
    }

    private boolean isIdentityProperty(List<PersistentProperty> identities, PersistentProperty prop) {
        for (PersistentProperty id : identities) {
            if (id.getName().equals(prop.getName())) {
                return true;
            }
        }
        return false;
    }

    private String escapeJsonFieldName(String fieldName) {
        return fieldName.replace("'", "''");
    }

    private Map<String, List<JoinPath>> groupJoinPathsByPrefix(Collection<JoinPath> joinPaths, String currentPrefix) {
        Map<String, List<JoinPath>> map = new LinkedHashMap<>();
        if (CollectionUtils.isEmpty(joinPaths)) {
            return map;
        }
        for (JoinPath jp : joinPaths) {
            String path = jp.getPath();
            if (StringUtils.isNotEmpty(currentPrefix)) {
                if (path.startsWith(currentPrefix + ".")) {
                    path = path.substring(currentPrefix.length() + 1);
                } else {
                    continue;
                }
            }
            int dotIndex = path.indexOf('.');
            String topSegment = dotIndex == -1 ? path : path.substring(0, dotIndex);
            map.computeIfAbsent(topSegment, k -> new ArrayList<>()).add(jp);
        }
        return map;
    }

    private Map<String, List<JoinPath>> stripPrefixFromJoinPaths(List<JoinPath> joinPaths, String prefix) {
        Map<String, List<JoinPath>> map = new LinkedHashMap<>();
        String prefixWithDot = prefix + ".";
        for (JoinPath jp : joinPaths) {
            String path = jp.getPath();
            if (path.startsWith(prefixWithDot)) {
                String subpath = path.substring(prefixWithDot.length());
                int dotIndex = subpath.indexOf('.');
                String topSegment = dotIndex == -1 ? subpath : subpath.substring(0, dotIndex);
                map.computeIfAbsent(topSegment, k -> new ArrayList<>()).add(jp);
            }
        }
        return map;
    }
}
