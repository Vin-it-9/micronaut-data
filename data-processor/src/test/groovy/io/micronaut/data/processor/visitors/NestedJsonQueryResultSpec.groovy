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
import io.micronaut.data.model.JsonDataType
import io.micronaut.data.model.query.builder.sql.Dialect

class NestedJsonQueryResultSpec extends AbstractDataSpec {

    void "test PostgreSQL auto-generated nested JSON query with independent to-many joins"() {
        given:
        def repository = buildRepository('test.ParentRepository', """
package test;

import io.micronaut.data.annotation.*;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;
import java.util.*;

@JdbcRepository(dialect = Dialect.POSTGRES)
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
        query.contains("'children', (SELECT json_agg(json_build_object('id', c_.\"id\", 'name', c_.\"name\", 'ord', c_.\"ord\") ORDER BY c_.\"ord\") FROM \"bench_child\" c_ WHERE c_.\"parent_id\" = parent_.\"id\")")
        query.contains("'tags', (SELECT json_agg(json_build_object('id', t_.\"id\", 'name', t_.\"name\", 'ord', t_.\"ord\") ORDER BY t_.\"ord\") FROM \"bench_tag\" t_ WHERE t_.\"parent_id\" = parent_.\"id\")")
        query.endsWith(') AS "DATA" FROM "bench_parent" parent_')
    }

    void "test Oracle auto-generated nested JSON query with native OSON and text return types"() {
        given:
        def repository = buildRepository('test.OracleParentRepository', """
package test;

import io.micronaut.data.annotation.*;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.JsonDataType;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;
import java.util.*;

@JdbcRepository(dialect = Dialect.ORACLE)
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
        findAllQuery.contains("'children' VALUE (SELECT JSON_ARRAYAGG(JSON_OBJECT('id' VALUE c_.\"ID\", 'name' VALUE c_.\"NAME\" RETURNING JSON) RETURNING JSON) FROM \"CHILD_ENTITY\" c_ WHERE c_.\"PARENT_ID\" = parent_entity_.\"ID\")")
        findAllQuery.contains('RETURNING JSON) AS "DATA" FROM "PARENT_ENTITY" parent_entity_')

        // Text CLOB: RETURNING CLOB at root, RETURNING JSON inside
        findByNameQuery.startsWith('SELECT JSON_OBJECT(')
        findByNameQuery.contains("'children' VALUE (SELECT JSON_ARRAYAGG(JSON_OBJECT('id' VALUE c_.\"ID\", 'name' VALUE c_.\"NAME\" RETURNING JSON) RETURNING JSON) FROM \"CHILD_ENTITY\" c_ WHERE c_.\"PARENT_ID\" = parent_entity_.\"ID\")")
        findByNameQuery.contains('RETURNING CLOB) AS "DATA" FROM "PARENT_ENTITY" parent_entity_ WHERE (parent_entity_."NAME" = ?)')

        // BLOB: RETURNING BLOB at root, RETURNING JSON inside
        findByIdQuery.startsWith('SELECT JSON_OBJECT(')
        findByIdQuery.contains("'children' VALUE (SELECT JSON_ARRAYAGG(JSON_OBJECT('id' VALUE c_.\"ID\", 'name' VALUE c_.\"NAME\" RETURNING JSON) RETURNING JSON) FROM \"CHILD_ENTITY\" c_ WHERE c_.\"PARENT_ID\" = parent_entity_.\"ID\")")
        findByIdQuery.contains('RETURNING BLOB) AS "DATA" FROM "PARENT_ENTITY" parent_entity_ WHERE (parent_entity_."ID" = ?)')
    }

    void "test nested associations and to-one relationships"() {
        given:
        def repository = buildRepository('test.CompanyRepository', """
package test;

import io.micronaut.data.annotation.*;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;
import java.util.*;

@JdbcRepository(dialect = Dialect.POSTGRES)
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
        query.contains("'employees', (SELECT json_agg(json_build_object('id', employee_.\"id\", 'empName', employee_.\"emp_name\")) FROM \"employee\" employee_ WHERE employee_.\"department_id\" = department_.\"id\")")
        query.contains("WHERE (company_.\"id\" = ?)")
    }

    void "test hand-written @Query methods with @QueryResult retain custom query"() {
        given:
        def repository = buildRepository('test.CustomJsonRepository', """
package test;

import io.micronaut.data.annotation.*;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;
import java.util.*;

@JdbcRepository(dialect = Dialect.POSTGRES)
interface CustomJsonRepository extends CrudRepository<SimpleEntity, Long> {

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
        def query = repository.getRequiredMethod("getCustom", Long).stringValue(Query).get()

        expect:
        query == "SELECT json_build_object('id', s.id, 'name', upper(s.name)) AS DATA FROM simple_entity s WHERE s.id = ?"
    }
}
