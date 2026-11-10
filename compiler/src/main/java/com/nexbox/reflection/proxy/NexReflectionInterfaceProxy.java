package com.nexbox.reflection.proxy;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;

import com.nexbox.reflection.NexReflectionInterfaceInfo;
import com.nexbox.reflection.annotation.BConstructor;
import com.nexbox.reflection.annotation.BConstructorNotProcess;
import com.nexbox.reflection.annotation.BFieldCheckNotProcess;
import com.nexbox.reflection.annotation.BFieldNotProcess;
import com.nexbox.reflection.annotation.BFieldSetNotProcess;
import com.nexbox.reflection.annotation.BMethodCheckNotProcess;
import com.nexbox.reflection.annotation.BParamClass;
import com.nexbox.reflection.annotation.BClassNameNotProcess;
import com.nexbox.reflection.annotation.BParamClassName;


public class NexReflectionInterfaceProxy {

    private final List<NexReflectionInterfaceInfo> mReflections = new ArrayList<>();
    
    private final String mClassName;
    
    private final String mPackageName;
    
    private final String mOrigClassName;
    private final Element mOriginatingElement;
    private Map<String, String> realMaps;

    public NexReflectionInterfaceProxy(String packageName, String className, String origClassName,
                                       Element originatingElement) {
        mPackageName = packageName;
        mClassName = className;
        mOrigClassName = origClassName;
        mOriginatingElement = originatingElement;
    }

    public Element getOriginatingElement() {
        return mOriginatingElement;
    }

    public JavaFile generateInterfaceCode() {
        String finalClass = mClassName
                .replace(mPackageName + ".", "")
                .replace(".", "");
        AnnotationSpec annotationSpec = AnnotationSpec.builder(BClassNameNotProcess.class)
                .addMember("value","$S", realMaps.get(mOrigClassName))
                .build();

        
        TypeSpec.Builder interfaceBuilder = TypeSpec.interfaceBuilder(finalClass)
                .addOriginatingElement(mOriginatingElement)
                .addAnnotation(annotationSpec)
                .addModifiers(Modifier.PUBLIC);

        for (NexReflectionInterfaceInfo reflection : mReflections) {
            MethodSpec.Builder method = MethodSpec.methodBuilder(reflection.getExecutableElement().getSimpleName().toString())
                    .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT);

            List<ParameterSpec> parameterSpecs = new ArrayList<>();
            for (VariableElement typeParameter : reflection.getExecutableElement().getParameters()) {
                // Parameter names are not part of the input ABI without -parameters.
                // Stable generated names keep incremental output independent of them.
                ParameterSpec.Builder builder = ParameterSpec.builder(ClassName.get(typeParameter.asType()),
                        "arg" + parameterSpecs.size());
                if (typeParameter.getAnnotation(BParamClassName.class) != null) {
                    BParamClassName annotation = typeParameter.getAnnotation(BParamClassName.class);
                    builder.addAnnotation(AnnotationSpec.get(annotation));
                }
                if (typeParameter.getAnnotation(BParamClass.class) != null) {
                    BParamClass annotation = typeParameter.getAnnotation(BParamClass.class);
                    String annotationValue = getClass(annotation).toString();
                    Class<?> aClass = parseBaseClass(annotationValue);
                    if (aClass != null) {
                        builder.addAnnotation(AnnotationSpec.builder(BParamClass.class)
                                .addMember("value", "$T.class", aClass).build());
                    } else {
                        builder.addAnnotation(AnnotationSpec.builder(BParamClass.class)
                                .addMember("value", annotationValue + ".class").build());
                    }
                }

                ParameterSpec build = builder.build();
                parameterSpecs.add(build);
                method.addParameter(build);
            }
            TypeName typeName = TypeName.get(reflection.getExecutableElement().getReturnType());
            method.returns(typeName.box());
            if (reflection.isField()) {
                method.addAnnotation(AnnotationSpec.builder(BFieldNotProcess.class).build());
                
                interfaceBuilder.addMethod(generateFieldSet(reflection));
                
                interfaceBuilder.addMethod(generateFieldCheck(reflection));
            } else {
                BConstructor annotation = reflection.getExecutableElement().getAnnotation(BConstructor.class);
                if (annotation != null) {
                    method.addAnnotation(AnnotationSpec.builder(BConstructorNotProcess.class).build());
                } else {
                    
                    interfaceBuilder.addMethod(generateMethodCheck(reflection, parameterSpecs));
                }
            }
            interfaceBuilder.addMethod(method.build());
        }
        return JavaFile.builder(mPackageName, interfaceBuilder.build()).build();
    }

    private MethodSpec generateFieldSet(NexReflectionInterfaceInfo reflection) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("_set_" + reflection.getExecutableElement().getSimpleName().toString())
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter(ClassName.get("java.lang", "Object"), "value", Modifier.FINAL)
                .addAnnotation(AnnotationSpec.builder(BFieldSetNotProcess.class).build());
        return method.build();
    }

    private MethodSpec generateFieldCheck(NexReflectionInterfaceInfo reflection) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("_check_" + reflection.getExecutableElement().getSimpleName().toString())
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addAnnotation(AnnotationSpec.builder(BFieldCheckNotProcess.class).build())
                .returns(Field.class);
        return method.build();
    }

    private MethodSpec generateMethodCheck(NexReflectionInterfaceInfo reflection, List<ParameterSpec> parameterSpecs) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("_check_" + reflection.getExecutableElement().getSimpleName().toString())
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addAnnotation(AnnotationSpec.builder(BMethodCheckNotProcess.class).build())
                .returns(Method.class);
        for (ParameterSpec parameterSpec : parameterSpecs) {
            method.addParameter(parameterSpec);
        }
        return method.build();
    }

    public void add(NexReflectionInterfaceInfo interfaceInfo) {
        mReflections.add(interfaceInfo);
    }

    public void setRealMap(Map<String, String> realMaps) {
        this.realMaps = realMaps;
    }

    private static TypeMirror getClass(BParamClass annotation) {
        try {
            annotation.value(); 
        } catch (MirroredTypeException mte) {
            return mte.getTypeMirror();
        }
        return null; 
    }

    private static Class<?> parseBaseClass(String className) {
        switch (className) {
            case "int":
                return int.class;
            case "byte":
                return byte.class;
            case "short":
                return short.class;
            case "long":
                return long.class;
            case "float":
                return float.class;
            case "double":
                return double.class;
            case "boolean":
                return boolean.class;
            case "char":
                return char.class;
            case "int[]":
                return int[].class;
            case "byte[]":
                return byte[].class;
            case "short[]":
                return short[].class;
            case "long[]":
                return long[].class;
            case "float[]":
                return float[].class;
            case "double[]":
                return double[].class;
            case "boolean[]":
                return boolean[].class;
            case "char[]":
                return char[].class;
        }
        return null;
    }
}
