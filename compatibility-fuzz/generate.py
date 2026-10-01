"""Regenerate the bounded CDI type grammar. No third-party Python dependencies."""
from pathlib import Path

atoms = ['String', 'Integer', 'Number', 'Object']
products = [(f'Box<{t}>', f'new Box<{t}>()') for t in atoms]
products += [(f'List<{t}>', 'new ArrayList<>()') for t in atoms]
products += [('ArrayList<String>', 'new ArrayList<>()'), ('Box<List<String>>', 'new Box<>()'),
             ('Box<String[]>', 'new Box<>()'), ('String[]', 'new String[0]'),
             ('List<String>[]', 'null'), ('Box', 'new Box()'), ('List', 'new ArrayList()'),
             ('int', '7'), ('Integer', '7')]
queries = atoms + ['int', 'Serializable', 'Cloneable', 'String[]', 'Object[]', 'List<String>[]', 'List<?>[]']
for raw in ['Box', 'View', 'List', 'Collection', 'ArrayList']:
    queries += [raw] + [f'{raw}<{arg}>' for arg in atoms + ['?', '? extends Number', '? super Integer', '? extends CharSequence', 'List<String>', 'List<?>', 'String[]']]
queries += ['Box<? super Number>', 'View<? super Number>']
header = '''package org.example.cdi.fuzz;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.util.TypeLiteral;
import java.lang.reflect.Type;
import java.util.*;
import java.io.Serializable;
public class TypeCorpus {
    public interface View<T> {}
    public static class Box<T> implements View<T> {}
'''
out = [header]
for i, (typ, value) in enumerate(products):
    out.append(f'    @Dependent public static class P{i} {{ @Produces public {typ} value() {{ return {value}; }} }}\n')
out += ['    @Dependent public static class Unbounded<T> extends Box<T> {}\n',
        '    @Dependent public static class Bounded<T extends Number> extends Box<T> {}\n',
        '    @Dependent public static class Recursive<T extends Comparable<T>> extends Box<T> {}\n',
        '    @Dependent public static class Multi<T extends Number & Comparable<T>> extends Box<T> {}\n',
        '    @Dependent public static class Nested<T> extends Box<List<T>> {}\n']
out.append('    public static final Class<?>[] PRODUCERS = { ' + ', '.join([f'P{i}.class' for i in range(len(products))] + [f'{n}.class' for n in ['Unbounded', 'Bounded', 'Recursive', 'Multi', 'Nested']]) + ' };\n')
out.append('    public static final Type[] QUERIES = {\n')
for q in queries:
    out.append(f'        {q}.class,\n' if '<' not in q else f'        new TypeLiteral<{q}>() {{}}.getType(),\n')
out.append('    };\n}\n')
Path(__file__).parent.joinpath('src/test/java/org/example/cdi/fuzz/TypeCorpus.java').write_text(''.join(out))
print(f'{len(products)+5} deployments x {len(queries)} required types = {(len(products)+5)*len(queries)} comparisons')
