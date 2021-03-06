package com.github.marschall.osgi.remoting.ejb.processor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.SimpleAnnotationValueVisitor6;
import javax.lang.model.util.SimpleElementVisitor6;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;

final class EjbCollector {

  final List<EjbInfo> beans;

  private final ProcessingEnvironment processingEnv;

  private final TypeElement stateless;

  private final TypeElement stateful;

  private final TypeElement singleton;

  private final TypeElement remote;

  private final Elements elements;

  private final Types types;


  EjbCollector(ProcessingEnvironment processingEnv) {
    this.processingEnv = processingEnv;
    this.beans = new ArrayList<EjbInfo>(3);
    
    this.elements = this.processingEnv.getElementUtils();
    this.types = this.processingEnv.getTypeUtils();
    
    this.stateless = this.elements.getTypeElement("javax.ejb.Stateless");
    this.stateful = this.elements.getTypeElement("javax.ejb.Stateful");
    this.singleton = this.elements.getTypeElement("javax.ejb.Singleton");
    this.remote = this.elements.getTypeElement("javax.ejb.Remote");
  }
  
  boolean isEmpty() {
    return this.beans.isEmpty();
  }
  

  public Element[] getElements() {
    Element[] elements = new Element[this.beans.size()];
    for (int i = 0; i < this.beans.size(); ++i) {
      elements[i] = this.beans.get(i).originatingElement;
    }
    return elements;
  }
  
  void processRound(RoundEnvironment roundEnv) {
    this.searchForBeansAnnotatedWith(roundEnv, this.stateless);
    this.searchForBeansAnnotatedWith(roundEnv, this.stateful);
    this.searchForBeansAnnotatedWith(roundEnv, this.singleton);
  }
  
  private void searchForBeansAnnotatedWith(RoundEnvironment roundEnv, TypeElement annotation) {
    boolean stateful = annotation.equals(this.stateful);
    Set<? extends Element> ejbs = roundEnv.getElementsAnnotatedWith(annotation);
    for (Element ejb : ejbs) {
      Messager messager = this.processingEnv.getMessager();
      messager.printMessage(Kind.NOTE, "processing ejb", ejb);
      AnnotationMirror remoteAnnotation = this.getRemoteAnnotation(ejb);
      List<String> remoteInterfaceNames;
      if (remoteAnnotation != null) {
        /*
         * @Remote({First.class, Second.class})
         * public class EJB implements First, Second {}
         */
        remoteInterfaceNames = this.getRemoteInterfaceNamesFromEjb(remoteAnnotation);
      } else {
        /*
         * @Remote
         * public interface First {}
         * 
         * @Remote
         * public interface Second {}
         */
        remoteInterfaceNames = this.getRemoteInterfaceNamesFromImplementation(ejb);
      }
      if (!remoteInterfaceNames.isEmpty()) {
        String nonQualifiedClassName = ejb.accept(SimpleTypeNameVisitor.INSTANCE, null);
        EjbInfo ejbInfo = new EjbInfo(nonQualifiedClassName, stateful, remoteInterfaceNames, ejb);
        this.beans.add(ejbInfo);
      }
    }
  }
  
  AnnotationMirror getRemoteAnnotation(Element ejb) {
    List<? extends AnnotationMirror> mirrors = elements.getAllAnnotationMirrors(ejb);
    for (AnnotationMirror annotationMirror : mirrors) {
      Element annotationElement = annotationMirror.getAnnotationType().asElement();
      if (remote.equals(annotationElement)) {
        return annotationMirror;
      }
    }
    return null;
  }
  
  private List<String> getRemoteInterfaceNamesFromImplementation(Element ejb) {
    List<? extends TypeMirror> implementedInterface = ejb.accept(ImplementedInterfacesVisitor.INSTANCE, this.types);
    List<TypeMirror> remoteInterfaces = this.filterNonRemoteInterfaces(implementedInterface);
    return getInterfaceNames(remoteInterfaces);
  }
  
  private List<String> getInterfaceNames(List<TypeMirror> remoteInterfaces) {
    List<String> interfaceNames = new ArrayList<String>(remoteInterfaces.size());
    for (TypeMirror typeMirror : remoteInterfaces) {
      Element element = this.types.asElement(typeMirror);
      String typeName = element.accept(QualifiedTypeNameVisitor.INSTANCE, null);
      interfaceNames.add(typeName);
    }
    return interfaceNames;
  }

  private List<TypeMirror> filterNonRemoteInterfaces(List<? extends TypeMirror> interfaces) {
    List<TypeMirror> remoteInterfaces = new ArrayList<TypeMirror>(interfaces.size());
    for (TypeMirror typeMirror : interfaces) {
      if (isRemoteInterface(typeMirror)) {
        remoteInterfaces.add(typeMirror);
      }
    }
    return remoteInterfaces;
  }
  
  private boolean isRemoteInterface(TypeMirror typeMirror) {
    Element element = this.types.asElement(typeMirror);
    return element.accept(IsAnnotatedWithRemote.INSTANCE, this);
  }

  private List<String> getRemoteInterfaceNamesFromEjb(AnnotationMirror annotationMirror) {
    Types types = this.processingEnv.getTypeUtils();
    List<String> interfaceNames = new ArrayList<String>(3);
    AnnotationValue value = getAnnotationValue(annotationMirror, "value");
    value.accept(new ClassNamesVisitor(types), interfaceNames);
    return interfaceNames;
  }
  
  private static AnnotationValue getAnnotationValue(AnnotationMirror mirror, String attributeName) {
    ExecutableElement method = getMethod(mirror, attributeName);
    if (method != null) {
      return mirror.getElementValues().get(method);
    } else {
      return null;
    }
  }
  
  private static ExecutableElement getMethod(AnnotationMirror mirror, String methodName) {
    Element element = mirror.getAnnotationType().asElement();
    List<ExecutableElement> methods = ElementFilter.methodsIn(element.getEnclosedElements());
    for (ExecutableElement method : methods) {
      if (method.getSimpleName().contentEquals(methodName)) {
        return method;
      }
    }
    return null;
  }
  
  static final class ClassNamesVisitor extends SimpleAnnotationValueVisitor6<List<String>, List<String>> {

    private final Types types;


    ClassNamesVisitor(Types types) {
      this.types = types;
    }

    @Override
    protected List<String> defaultAction(Object object, List<String> accumulator) {
      return accumulator;
    }

    @Override
    public List<String> visitArray(List<? extends AnnotationValue> vals, List<String> accumulator) {
      for (AnnotationValue annoationValue : vals) {
        annoationValue.accept(this, accumulator);
      }
      return super.visitArray(vals, accumulator);
    }

    @Override
    public List<String> visitType(TypeMirror type, List<String> accumulator) {
      Element element = this.types.asElement(type);
      String typeName = element.accept(QualifiedTypeNameVisitor.INSTANCE, null);
      if (typeName != null) {
        accumulator.add(typeName);
      }
      return accumulator;
    }

  }
  
  static final class QualifiedTypeNameVisitor extends SimpleElementVisitor6<String, Void> {
    
    static final ElementVisitor<String, Void> INSTANCE = new QualifiedTypeNameVisitor();

    @Override
    public String visitType(TypeElement type, Void p) {
      return type.getQualifiedName().toString();
    }

  }
  
  static final class SimpleTypeNameVisitor extends SimpleElementVisitor6<String, Void> {
    
    static final ElementVisitor<String, Void> INSTANCE = new SimpleTypeNameVisitor();
    
    @Override
    public String visitType(TypeElement type, Void p) {
      return type.getSimpleName().toString();
    }
    
  }
  
  static final class IsAnnotatedWithRemote extends SimpleElementVisitor6<Boolean, EjbCollector> {

    static final ElementVisitor<Boolean, EjbCollector> INSTANCE = new IsAnnotatedWithRemote();

    @Override
    public Boolean visitType(TypeElement type, EjbCollector collector) {
      return collector.getRemoteAnnotation(type) != null;
    }
  }
  
  static final class ImplementedInterfacesVisitor extends SimpleElementVisitor6<List<? extends TypeMirror>, Types> {
    
    static final ElementVisitor<List<? extends TypeMirror>, Types> INSTANCE = new ImplementedInterfacesVisitor();
    
    @Override
    public List<? extends TypeMirror> visitType(TypeElement type, Types types) {
      List<? extends TypeMirror> directInterfaces = type.getInterfaces();
      TypeMirror superclass = type.getSuperclass();
      if (superclass.getKind() == TypeKind.NONE) {
        return directInterfaces;
      } else {
        List<? extends TypeMirror> parentInterfaces = types.asElement(superclass).accept(this, types);
        // TODO optimize
        List<TypeMirror> allInterfaces = new ArrayList<TypeMirror>(directInterfaces.size() + parentInterfaces.size());
        allInterfaces.addAll(directInterfaces);
        allInterfaces.addAll(parentInterfaces);
        return allInterfaces;
      }
    }
    
  }
}
