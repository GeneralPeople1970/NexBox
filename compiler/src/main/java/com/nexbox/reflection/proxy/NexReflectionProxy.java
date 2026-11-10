package com.nexbox.reflection.proxy;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.WildcardTypeName;

import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;

import com.nexbox.reflection.NexReflectionInfo;
import com.nexbox.reflection.utils.ClassUtils;


public class NexReflectionProxy {

    private static final ClassName BR = ClassName.get("com.nexbox.reflection", "NexReflection");
    private final NexReflectionInfo mReflection;

    private final ClassName mContextInterface;
    private final ClassName mStaticInterface;
    private final String mPackageName;
    private final Element mOriginatingElement;

    public NexReflectionProxy(String packageName, NexReflectionInfo reflection, Element originatingElement) {
        mReflection = reflection;
        mPackageName = packageName;
        mOriginatingElement = originatingElement;
        String finalClass = mReflection.getClassName()
                .replace(packageName + ".", "")
                .replace(".", "");

        mContextInterface = ClassName.get(ClassUtils.getPackage(finalClass), ClassUtils.getName(finalClass + "Context"));
        mStaticInterface = ClassName.get(ClassUtils.getPackage(finalClass), ClassUtils.getName(finalClass + "Static"));
    }

    public Element getOriginatingElement() {
        return mOriginatingElement;
    }

    public JavaFile generateJavaCode() {
        String finalClass = "BR" + mReflection.getClassName()
                .replace(mPackageName + ".", "")
                .replace(".", "");

        
        TypeSpec reflection = TypeSpec.classBuilder(finalClass)
                .addOriginatingElement(mOriginatingElement)
                .addModifiers(Modifier.PUBLIC)
                .addMethod(generaNotCallerMethod(true))
                .addMethod(generaNotCallerMethod(false))
                .addMethod(generaCallerMethod(true))
                .addMethod(generaCallerMethod(false))
                .addMethod(generaIsLoadMethod())
                .build();
        return JavaFile.builder(mPackageName, reflection).build();
    }

    private MethodSpec generaNotCallerMethod(boolean withException) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("get" + (withException ? "WithException" : ""))
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(mStaticInterface);
        String statement = "return $T.create($T.class, null, $L)";
        builder.addStatement(statement,
                BR,
                mStaticInterface,
                withException
        );
        return builder.build();
    }

    private MethodSpec generaCallerMethod(boolean withException) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("get" + (withException ? "WithException" : ""))
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(ClassName.get("java.lang", "Object"), "caller", Modifier.FINAL)
                .returns(mContextInterface);

        String statement = "return $T.create($T.class, caller, $L)";
        builder.addStatement(statement,
                BR,
                mContextInterface,
                withException
        );
        return builder.build();
    }

    private MethodSpec generaIsLoadMethod() {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("getRealClass")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ParameterizedTypeName.get(ClassName.get(Class.class),
                        WildcardTypeName.subtypeOf(Object.class)));

        String statement = "return com.nexbox.reflection.utils.ClassUtil.classReady($T.class)";
        builder.addStatement(statement,
                mContextInterface
        );
        return builder.build();
    }
}
