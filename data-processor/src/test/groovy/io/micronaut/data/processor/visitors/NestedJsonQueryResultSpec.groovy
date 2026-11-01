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
package io.micronaut.data.processor.visitors

import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.QueryResult
import io.micronaut.data.exceptions.MappingException
import io.micronaut.data.model.JsonDataType
import io.micronaut.data.model.query.builder.sql.Dialect

class NestedJsonQueryResultSpec extends AbstractDataSpec {

    void "test PostgreSQL auto-generated nested JSON query with independent to-many joins"() {
        given:
        def repository = buildRepository('test.ParentRepository', """
import io.micronaut.data.annotation.*;
import io.micronaut.data.model.query.builder.sql.Dialect;

@io.micronaut.data.jdbc.annotation.JdbcRepository(dialect = Dialect.POSTGRES)
interface ParentRepository extends CrudRepository<Parent, Long> {

    @Override
    @Join(value = "children", alias = "c_", type = Join.Type.LEFT_FETCH)
    @Join(value = "tags", alias = "t_", type = Join.Type.LEFT_FETCH)
    @QueryResult(type = QueryResult.Type.JSON)
    List<Parent> findAll();
}

@MappedEntity("bench_parent")
class Parent {
    @Id
    @GeneratedValue
    private Long id;
    private String name;

    @Relation(value = Relation.Kind.ONE_TO_MANY, mappedBy = "parent")
    private List<Child> children;

    @Relation(value = Relation.Kind.ONE_TO_MANY, mappedBy = "parent")
    private List<Tag> tags;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<Child> getChildren() { return children; }
    public void setChildren(List<Child> children) { this.children = children; }
    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}

@MappedEntity("bench_child")
class Child {
    @Id
    @GeneratedValue
    private Long id;
    private String name;
    private Integer ord;

    @Relation(Relation.Kind.MANY_TO_ONE)
    private Parent parent;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getOrd() { return ord; }
    public void setOrd(Integer ord) { this.ord = ord; }
    public Parent getParent() { return parent; }
    public void setParent(Parent parent) { this.parent = parent; }
}

@MappedEntity("bench_tag")
class Tag {
    @Id
    @GeneratedValue
    private Long id;
    private String name;
    private Integer ord;

    @Relation(Relation.Kind.MANY_TO_ONE)
    private Parent parent;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getOrd() { return ord; }
    public void setOrd(Integer ord) { this.ord = ord; }
    public Parent getParent() { return parent; }
    public void setParent(Parent parent) { this.parent = parent; }
}
""")
        def query = repository.getRequiredMethod("findAll").stringValue(Query).get()

        expect:
        // Root FROM must not have outer LEFT JOINs that cause Cartesian product
        !query.contains('FROM "bench_parent" parent_ LEFT JOIN')
        query.startsWith('SELECT json_build_object(')
        query.contains("'id', parent_.\"id\"")
        query.contains("'name', parent_.\"name\"")
        query.contains("'children', (SELECT COALESCE(json_agg(json_build_object('id', c_.\"id\", 'name', c_.\"name\", 'ord', c_.\"ord\") ORDER BY c_.\"ord\"), json_build_array()) FROM \"bench_child\" c_ WHERE c_.\"parent_id\" = parent_.\"id\")")
        query.contains("'tags', (SELECT COALESCE(json_agg(json_build_object('id', t_.\"id\", 'name', t_.\"name\", 'ord', t_.\"ord\") ORDER BY t_.\"ord\"), json_build_array()) FROM \"bench_tag\" t_ WHERE t_.\"parent_id\" = parent_.\"id\")")
        query.endsWith(') AS "DATA" FROM "bench_parent" parent_')
    }

    void "test Oracle auto-generated nested JSON query with native OSON and text return types"() {
        given:
        def repository = buildRepository('test.OracleParentRepository', """
import io.micronaut.data.annotation.*;
import io.micronaut.data.model.JsonDataType;
import io.micronaut.data.model.query.builder.sql.Dialect;

@io.micronaut.data.jdbc.annotation.JdbcRepository(dialect = Dialect.ORACLE)
interface OracleParentRepository extends CrudRepository<ParentEntity, Long> {

    @Override
    @Join(value = "children", alias = "c_", type = Join.Type.LEFT_FETCH)
    @QueryResult(type = QueryResult.Type.JSON, jsonDataType = JsonDataType.DEFAULT)
    List<ParentEntity> findAll();

    @Join(value = "children", alias = "c_", type = Join.Type.LEFT_FETCH)
    @QueryResult(type = QueryResult.Type.JSON, jsonDataType = JsonDataType.STRING)
    List<ParentEntity> findByName(String name);

    @Join(value = "children", alias = "c_", type = Join.Type.LEFT_FETCH)
    @QueryResult(type = QueryResult.Type.JSON, jsonDataType = JsonDataType.BLOB)
    Optional<ParentEntity> findById(Long id);
}

@MappedEntity("PARENT_ENTITY")
class ParentEntity {
    @Id
    @GeneratedValue
    private Long id;
    private String name;

    @Relation(value = Relation.Kind.ONE_TO_MANY, mappedBy = "parent")
    private List<ChildEntity> children;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<ChildEntity> getChildren() { return children; }
    public void setChildren(List<ChildEntity> children) { this.children = children; }
}

@MappedEntity("CHILD_ENTITY")
class ChildEntity {
    @Id
    @GeneratedValue
    private Long id;
    private String name;

    @Relation(Relation.Kind.MANY_TO_ONE)
    private ParentEntity parent;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public ParentEntity getParent() { return parent; }
    public void setParent(ParentEntity parent) { this.parent = parent; }
}
""")
        def findAllQuery = repository.getRequiredMethod("findAll").stringValue(Query).get()
        def findByNameQuery = repository.getRequiredMethod("findByName", String).stringValue(Query).get()
        def findByIdQuery = repository.getRequiredMethod("findById", Long).stringValue(Query).get()

        expect:
        // Native JSON: RETURNING JSON
        findAllQuery.startsWith('SELECT JSON_OBJECT(')
        findAllQuery.contains("'children' VALUE (SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('id' VALUE c_.\"ID\", 'name' VALUE c_.\"NAME\" RETURNING JSON) RETURNING JSON), JSON_ARRAY(RETURNING JSON)) FROM \"CHILD_ENTITY\" c_ WHERE c_.\"PARENT_ID\" = parent_entity_.\"ID\")")
        findAllQuery.contains('RETURNING JSON) AS "DATA" FROM "PARENT_ENTITY" parent_entity_')

        // Text CLOB: RETURNING CLOB at root, RETURNING JSON inside
        findByNameQuery.startsWith('SELECT JSON_OBJECT(')
        findByNameQuery.contains("'children' VALUE (SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('id' VALUE c_.\"ID\", 'name' VALUE c_.\"NAME\" RETURNING JSON) RETURNING JSON), JSON_ARRAY(RETURNING JSON)) FROM \"CHILD_ENTITY\" c_ WHERE c_.\"PARENT_ID\" = parent_entity_.\"ID\")")
        findByNameQuery.contains('RETURNING CLOB) AS "DATA" FROM "PARENT_ENTITY" parent_entity_ WHERE (parent_entity_."NAME" = ?)')

        // BLOB: RETURNING BLOB at root, RETURNING JSON inside
        findByIdQuery.startsWith('SELECT JSON_OBJECT(')
        findByIdQuery.contains("'children' VALUE (SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('id' VALUE c_.\"ID\", 'name' VALUE c_.\"NAME\" RETURNING JSON) RETURNING JSON), JSON_ARRAY(RETURNING JSON)) FROM \"CHILD_ENTITY\" c_ WHERE c_.\"PARENT_ID\" = parent_entity_.\"ID\")")
        findByIdQuery.contains('RETURNING BLOB) AS "DATA" FROM "PARENT_ENTITY" parent_entity_ WHERE (parent_entity_."ID" = ?)')
    }

    void "test nested associations and to-one relationships"() {
        given:
        def repository = buildRepository('test.CompanyRepository', """
import io.micronaut.data.annotation.*;
import io.micronaut.data.model.query.builder.sql.Dialect;

@io.micronaut.data.jdbc.annotation.JdbcRepository(dialect = Dialect.POSTGRES)
interface CompanyRepository extends CrudRepository<Company, Long> {

    @Join(value = "address", type = Join.Type.LEFT_FETCH)
    @Join(value = "departments", type = Join.Type.LEFT_FETCH)
    @Join(value = "departments.employees", type = Join.Type.LEFT_FETCH)
    @QueryResult(type = QueryResult.Type.JSON)
    Optional<Company> findById(Long id);
}

@MappedEntity("company")
class Company {
    @Id
    @GeneratedValue
    private Long id;
    private String name;

    @Relation(Relation.Kind.MANY_TO_ONE)
    private Address address;

    @Relation(value = Relation.Kind.ONE_TO_MANY, mappedBy = "company")
    private List<Department> departments;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Address getAddress() { return address; }
    public void setAddress(Address address) { this.address = address; }
    public List<Department> getDepartments() { return departments; }
    public void setDepartments(List<Department> departments) { this.departments = departments; }
}

@MappedEntity("address")
class Address {
    @Id
    @GeneratedValue
    private Long id;
    private String street;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStreet() { return street; }
    public void setStreet(String street) { this.street = street; }
}

@MappedEntity("department")
class Department {
    @Id
    @GeneratedValue
    private Long id;
    private String deptName;

    @Relation(Relation.Kind.MANY_TO_ONE)
    private Company company;

    @Relation(value = Relation.Kind.ONE_TO_MANY, mappedBy = "department")
    private List<Employee> employees;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDeptName() { return deptName; }
    public void setDeptName(String deptName) { this.deptName = deptName; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public List<Employee> getEmployees() { return employees; }
    public void setEmployees(List<Employee> employees) { this.employees = employees; }
}

@MappedEntity("employee")
class Employee {
    @Id
    @GeneratedValue
    private Long id;
    private String empName;

    @Relation(Relation.Kind.MANY_TO_ONE)
    private Department department;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEmpName() { return empName; }
    public void setEmpName(String empName) { this.empName = empName; }
    public Department getDepartment() { return department; }
    public void setDepartment(Department department) { this.department = department; }
}
""")
        def query = repository.getRequiredMethod("findById", Long).stringValue(Query).get()

        expect:
        query.startsWith('SELECT json_build_object(')
        // To-one relationship address
        query.contains("'address', (SELECT json_build_object('id', address_.\"id\", 'street', address_.\"street\") FROM \"address\" address_ WHERE address_.\"id\" = company_.\"address_id\")")
        // Nested to-many departments -> employees
        query.contains("'employees', (SELECT COALESCE(json_agg(json_build_object('id', employees_.\"id\", 'empName', employees_.\"emp_name\")), json_build_array()) FROM \"employee\" employees_ WHERE employees_.\"department_id\" = departments_.\"id\")")
        query.contains("WHERE (company_.\"id\" = ?)")
    }

    void "test nested JSON follows serialization property names and ignored properties"() {
        given:
        def repository = buildRepository('test.SerializedPropertyRepository', """
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.data.annotation.*;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;

@JdbcRepository(dialect = Dialect.POSTGRES)
interface SerializedPropertyRepository extends CrudRepository<SerializedPropertyEntity, Long> {
    @Join(value = "profile", type = Join.Type.LEFT_FETCH)
    @QueryResult(type = QueryResult.Type.JSON)
    List<SerializedPropertyEntity> findAll();
}

@MappedEntity("serialized_property_entity")
class SerializedPropertyEntity {
    @Id
    @GeneratedValue
    private Long id;
    @JsonProperty("display_name")
    private String name;
    @JsonIgnore
    private String internalNote;
    @Relation(Relation.Kind.ONE_TO_ONE)
    private SerializedProfile profile;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getInternalNote() { return internalNote; }
    public void setInternalNote(String internalNote) { this.internalNote = internalNote; }
    public SerializedProfile getProfile() { return profile; }
    public void setProfile(SerializedProfile profile) { this.profile = profile; }
}

@MappedEntity("serialized_profile")
class SerializedProfile {
    @Id
    @GeneratedValue
    private Long id;
    private String value;
}
""")
        def query = getQuery(repository.getRequiredMethod("findAll"))

        expect:
        query.contains("'display_name', serialized_property_entity_.\"name\"")
        !query.contains("internal_note")
        query.contains("'profile'")
        query.contains('FROM "serialized_profile"')
    }

    void "test nested JSON supports many to many join tables"() {
        given:
        def repository = buildRepository('test.PlaylistRepository', """
import io.micronaut.data.annotation.*;
import io.micronaut.data.annotation.sql.JoinColumn;
import io.micronaut.data.annotation.sql.JoinTable;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;

@JdbcRepository(dialect = Dialect.POSTGRES)
interface PlaylistRepository extends CrudRepository<Playlist, Long> {
    @Join(value = "tracks", type = Join.Type.LEFT_FETCH)
    @QueryResult(type = QueryResult.Type.JSON)
    List<Playlist> findAll();
}

@MappedEntity("playlist")
class Playlist {
    @Id
    @GeneratedValue
    private Long id;
    private String name;
    @JoinTable(name = "playlist_track_link",
        joinColumns = @JoinColumn(name = "playlist_id"),
        inverseJoinColumns = @JoinColumn(name = "track_id"))
    @Relation(Relation.Kind.MANY_TO_MANY)
    private List<Track> tracks;
}

@MappedEntity("track")
class Track {
    @Id
    @GeneratedValue
    private Long id;
    private String title;
}
""")
        def query = getQuery(repository.getRequiredMethod("findAll"))

        expect:
        query.contains("'tracks', (SELECT COALESCE(json_agg(json_build_object(")
        query.contains('FROM "track" tracks_')
        query.contains('JOIN "playlist_track_link" tracks__jt_ ON tracks__jt_."track_id" = tracks_."id"')
        query.contains('WHERE tracks__jt_."playlist_id" = playlist_."id"')
    }

    void "test nested JSON preserves filtering ordering pagination and count semantics"() {
        given:
        def repository = buildRepository('test.PagedParentRepository', """
import io.micronaut.data.annotation.*;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;

@JdbcRepository(dialect = Dialect.POSTGRES)
interface PagedParentRepository extends CrudRepository<PagedParent, Long> {

    @Join(value = "children", alias = "c_", type = Join.Type.LEFT_FETCH)
    @Join(value = "tags", alias = "t_", type = Join.Type.LEFT_FETCH)
    @QueryResult(type = QueryResult.Type.JSON)
    Page<PagedParent> findByNameOrderByName(String name, Pageable pageable);
}

@MappedEntity("paged_parent")
class PagedParent {
    @Id
    @GeneratedValue
    private Long id;
    private String name;

    @Relation(value = Relation.Kind.ONE_TO_MANY, mappedBy = "parent")
    private List<PagedChild> children;

    @Relation(value = Relation.Kind.ONE_TO_MANY, mappedBy = "parent")
    private List<PagedTag> tags;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<PagedChild> getChildren() { return children; }
    public void setChildren(List<PagedChild> children) { this.children = children; }
    public List<PagedTag> getTags() { return tags; }
    public void setTags(List<PagedTag> tags) { this.tags = tags; }
}

@MappedEntity("paged_child")
class PagedChild {
    @Id
    @GeneratedValue
    private Long id;
    private String name;
    @Relation(Relation.Kind.MANY_TO_ONE)
    private PagedParent parent;
}

@MappedEntity("paged_tag")
class PagedTag {
    @Id
    @GeneratedValue
    private Long id;
    private String name;
    @Relation(Relation.Kind.MANY_TO_ONE)
    private PagedParent parent;
}
""")
        def method = repository.getRequiredMethod("findByNameOrderByName", String, Pageable)
        def query = getQuery(method)
        def countQuery = getCountQuery(method)

        expect:
        query.startsWith('SELECT json_build_object(')
        query.contains('FROM "paged_parent" paged_parent_')
        query.contains('WHERE (paged_parent_."name" = ?)')
        query.contains('ORDER BY paged_parent_."name" ASC')
        !query.contains('LEFT JOIN')
        countQuery.startsWith('SELECT COUNT(')
        countQuery.contains('FROM "paged_parent" paged_parent_')
        countQuery.contains('WHERE (paged_parent_."name" = ?)')
        !countQuery.contains('json_build_object')
        !countQuery.contains('LIMIT')

    }

    void "test nested JSON preserves scalar JSON values and SQL read transformers"() {
        given:
        def repository = buildRepository('test.ScalarRepository', """
import io.micronaut.data.annotation.*;
import io.micronaut.data.annotation.sql.ColumnTransformer;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;

@JdbcRepository(dialect = Dialect.POSTGRES)
interface ScalarRepository extends CrudRepository<ScalarEntity, Long> {
    @QueryResult(type = QueryResult.Type.JSON)
    List<ScalarEntity> findAll();
}

@MappedEntity("scalar_entity")
class ScalarEntity {
    @Id
    @GeneratedValue
    private Long id;
    private java.math.BigDecimal amount;
    private java.time.LocalDate createdDate;
    private java.time.Instant observedAt;
    @ColumnTransformer(read = "UPPER(@.code)")
    private String code;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public java.math.BigDecimal getAmount() { return amount; }
    public void setAmount(java.math.BigDecimal amount) { this.amount = amount; }
    public java.time.LocalDate getCreatedDate() { return createdDate; }
    public void setCreatedDate(java.time.LocalDate createdDate) { this.createdDate = createdDate; }
    public java.time.Instant getObservedAt() { return observedAt; }
    public void setObservedAt(java.time.Instant observedAt) { this.observedAt = observedAt; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
}
""")
        def query = getQuery(repository.getRequiredMethod("findAll"))

        expect:
        query.contains("'id', scalar_entity_.\"id\"")
        query.contains("'amount', scalar_entity_.\"amount\"")
        query.contains("'createdDate', scalar_entity_.\"created_date\"")
        query.contains("'observedAt', scalar_entity_.\"observed_at\"")
        query.contains("'code', UPPER(scalar_entity_.\"code\")")
    }

    void "test nested JSON diagnoses unsupported attribute converters"() {
        when:
        buildRepository('test.ConverterRepository', """
import io.micronaut.data.annotation.*;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.model.runtime.convert.AttributeConverter;
import io.micronaut.core.convert.ConversionContext;

@JdbcRepository(dialect = Dialect.POSTGRES)
interface ConverterRepository extends CrudRepository<ConvertedEntity, Long> {
    @QueryResult(type = QueryResult.Type.JSON)
    List<ConvertedEntity> findAll();
}

@MappedEntity("converted_entity")
class ConvertedEntity {
    @Id
    @GeneratedValue
    private Long id;
    @MappedProperty(converter = StringConverter.class)
    private String code;
}

class StringConverter implements AttributeConverter<String, String> {
    public String convertToPersistedValue(String value, ConversionContext context) { return value; }
    public String convertToEntityValue(String value, ConversionContext context) { return value; }
}
""")

        then:
        def exception = thrown(MappingException)
        exception.message.contains('does not support attribute converter')
        exception.message.contains('ConvertedEntity.code')
    }

    void "test PostgreSQL and Oracle generate equivalent selected graph shapes"() {
        given:
        def graphSource = { String repositoryName, String dialect -> """
import io.micronaut.data.annotation.*;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;

@JdbcRepository(dialect = Dialect.${dialect})
interface ${repositoryName} extends CrudRepository<GraphRoot, Long> {
    @Join(value = "address", type = Join.Type.LEFT_FETCH)
    @Join(value = "children", type = Join.Type.LEFT_FETCH)
    @QueryResult(type = QueryResult.Type.JSON)
    List<GraphRoot> findAll();
}

@MappedEntity("graph_root")
class GraphRoot {
    @Id
    @GeneratedValue
    private Long id;
    private String name;
    @Relation(Relation.Kind.MANY_TO_ONE)
    private GraphAddress address;
    @Relation(value = Relation.Kind.ONE_TO_MANY, mappedBy = "root")
    private List<GraphChild> children;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public GraphAddress getAddress() { return address; }
    public void setAddress(GraphAddress address) { this.address = address; }
    public List<GraphChild> getChildren() { return children; }
    public void setChildren(List<GraphChild> children) { this.children = children; }
}

@MappedEntity("graph_address")
class GraphAddress {
    @Id
    @GeneratedValue
    private Long id;
    private String city;
}

@MappedEntity("graph_child")
class GraphChild {
    @Id
    @GeneratedValue
    private Long id;
    private String name;
    @Relation(Relation.Kind.MANY_TO_ONE)
    private GraphRoot root;
}
""" }
        def postgresRepository = buildRepository('test.PostgresGraphRepository', graphSource('PostgresGraphRepository', 'POSTGRES'))
        def oracleRepository = buildRepository('test.OracleGraphRepository', graphSource('OracleGraphRepository', 'ORACLE'))
        def postgresQuery = getQuery(postgresRepository.getRequiredMethod("findAll"))
        def oracleQuery = getQuery(oracleRepository.getRequiredMethod("findAll"))

        expect:
        postgresQuery.startsWith('SELECT json_build_object(')
        oracleQuery.startsWith('SELECT JSON_OBJECT(')
        [postgresQuery, oracleQuery].collect { it.toLowerCase() }.every {
            it.contains("'id'")
                && it.contains("'name'")
                && it.contains("'address'")
                && it.contains("'children'")
                && it.contains('FROM "graph_root"')
                && it.contains('FROM "graph_address"')
                && it.contains('FROM "graph_child"')
        }
        postgresQuery.toLowerCase().contains('graph_address_')
        oracleQuery.toLowerCase().contains('graph_address_')
        postgresQuery.toLowerCase().contains('graph_child_')
        oracleQuery.toLowerCase().contains('graph_child_')
    }

    void "test hand-written @Query methods with @QueryResult retain custom query"() {
        given:
        def repository = buildRepository('test.CustomJsonRepository', """
import io.micronaut.data.annotation.*;
import io.micronaut.data.model.query.builder.sql.Dialect;

@io.micronaut.data.jdbc.annotation.JdbcRepository(dialect = Dialect.POSTGRES)
interface CustomJsonRepository extends CrudRepository<SimpleEntity, Long> {

    @Override
    List<SimpleEntity> findAll();

    @Query("SELECT json_build_object('id', s.id, 'name', upper(s.name)) AS DATA FROM simple_entity s WHERE s.id = :id")
    @QueryResult(type = QueryResult.Type.JSON)
    Optional<SimpleEntity> getCustom(Long id);
}

@MappedEntity("simple_entity")
class SimpleEntity {
    @Id
    @GeneratedValue
    private Long id;
    private String name;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
""")
        def tabularQuery = getQuery(repository.getRequiredMethod("findAll"))
        def query = repository.getRequiredMethod("getCustom", Long).stringValue(Query).get()

        expect:
        !tabularQuery.contains('json_build_object')
        tabularQuery.contains('FROM "simple_entity" simple_entity_')
        query == "SELECT json_build_object('id', s.id, 'name', upper(s.name)) AS DATA FROM simple_entity s WHERE s.id = :id"
    }
}
