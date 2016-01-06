package com.sourcegraph.toolchain.cpp;

import com.sourcegraph.toolchain.core.objects.Def;
import com.sourcegraph.toolchain.core.objects.DefData;
import com.sourcegraph.toolchain.core.objects.DefKey;
import com.sourcegraph.toolchain.core.objects.Ref;
import com.sourcegraph.toolchain.cpp.antlr4.CPP14BaseListener;
import com.sourcegraph.toolchain.language.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.apache.commons.lang3.StringUtils;

import java.util.Collection;
import java.util.LinkedList;
import java.util.Stack;

import static com.sourcegraph.toolchain.cpp.antlr4.CPP14Parser.*;

class CPPParseTreeListener extends CPP14BaseListener {

    private static final char PATH_SEPARATOR = '.';

    private static final String UNKNOWN = "?";

    private LanguageImpl support;

    private Context<Variable> context = new Context<>();

    private Stack<String> typeStack = new Stack<>();

    private Stack<ParserRuleContext> fnCallStack = new Stack<>();

    private String currentClass;

    private boolean isInFunction;

    CPPParseTreeListener(LanguageImpl support) {
        this.support = support;
    }

    @Override
    public void enterOriginalnamespacedefinition(OriginalnamespacedefinitionContext ctx) {
        context.enterScope(new Scope<>(ctx.Identifier().getText(), context.getPrefix(PATH_SEPARATOR)));
    }

    @Override
    public void exitOriginalnamespacedefinition(OriginalnamespacedefinitionContext ctx) {
        context.exitScope();
    }

    @Override
    public void enterExtensionnamespacedefinition(ExtensionnamespacedefinitionContext ctx) {
        context.enterScope(new Scope<>(ctx.originalnamespacename().getText(), context.getPrefix(PATH_SEPARATOR)));
    }

    @Override
    public void exitExtensionnamespacedefinition(ExtensionnamespacedefinitionContext ctx) {
        context.exitScope();
    }

    @Override
    public void enterUnnamednamespacedefinition(UnnamednamespacedefinitionContext ctx) {
        context.enterScope(context.currentScope().next(PATH_SEPARATOR));
    }

    @Override
    public void exitUnnamednamespacedefinition(UnnamednamespacedefinitionContext ctx) {
        context.exitScope();
    }

    @Override
    public void enterClassspecifier(ClassspecifierContext ctx) {

        ClassheadContext head = ctx.classhead();
        ParserRuleContext name = head.classheadname().classname();
        String className = name.getText();

        Scope<Variable> scope = new Scope<>(className, context.getPrefix(PATH_SEPARATOR));

        String kind = head.classkey().getText();
        Def def = support.def(name, kind);
        def.defKey = new DefKey(null, context.currentScope().getPathTo(className, PATH_SEPARATOR));
        def.format(kind, kind, DefData.SEPARATOR_SPACE);
        support.emit(def);

        BaseclauseContext base = head.baseclause();
        if (base != null) {
            emitBaseClasses(base.basespecifierlist());
        }

        context.enterScope(scope);

        String path = context.getPath(PATH_SEPARATOR);
        support.infos.setData(path, scope);
        currentClass = path;

        // we should handle members here instead of enterMemberdeclaration()
        // because they may appear after method declaration while we need to know this info
        processMembers(ctx.memberspecification());
    }

    @Override
    public void exitClassspecifier(ClassspecifierContext ctx) {
        context.exitScope();
        currentClass = null;
    }

    @Override
    public void enterSimpledeclaration(SimpledeclarationContext ctx) {
        processDeclarationVariables(ctx.initdeclaratorlist(),
                processDeclarationType(ctx));

    }

    @Override
    public void enterFunctiondefinition(FunctiondefinitionContext ctx) {
        TypespecifierContext typeCtx = getDeclTypeSpecifier(ctx.declspecifierseq());
        String returnType;
        if (typeCtx != null) {
            returnType = processTypeSpecifier(typeCtx);
        } else {
            // ctor?
            returnType = context.currentScope().getPath();
        }

        String path = context.currentScope().getPath();

        FunctionParameters params = new FunctionParameters();
        ParametersandqualifiersContext paramsCtx = getParametersAndQualifiers(ctx.declarator());
        if (paramsCtx != null) {
            processFunctionParameters(
                    paramsCtx.parameterdeclarationclause().parameterdeclarationlist(),
                    params);
        }

        ParserRuleContext ident = getIdentifier(ctx.declarator());
        Def fnDef = support.def(ident, DefKind.FUNCTION);

        String fnPath = fnDef.name + '(' + params.getSignature() + ')';
        fnDef.defKey = new DefKey(null, context.currentScope().getPathTo(fnPath, PATH_SEPARATOR));

        context.enterScope(new Scope<>(fnPath, context.currentScope().getPrefix()));


        StringBuilder repr = new StringBuilder().append('(').append(params.getRepresentation()).append(')');
        repr.append(' ').append(returnType);
        fnDef.format(StringUtils.EMPTY, repr.toString(), DefData.SEPARATOR_EMPTY);
        fnDef.defData.setKind(DefKind.FUNCTION);
        support.emit(fnDef);

        for (FunctionParameter param : params.params) {
            param.def.defKey = new DefKey(null, context.currentScope().getPathTo(param.def.name, PATH_SEPARATOR));
            support.emit(param.def);
            context.currentScope().put(param.name, new Variable(param.type));
        }
        support.infos.setProperty(path, DefKind.FUNCTION, fnPath, returnType);

    }

    @Override
    public void exitFunctiondefinition(FunctiondefinitionContext ctx) {
        context.exitScope();
    }

    @Override
    public void exitPrimarypostfixexpression(PrimarypostfixexpressionContext ctx) {

        PrimaryexpressionContext ident = ctx.primaryexpression();
        IdexpressionContext idexpr = ident.idexpression();

        // foo or foo()
        if (ctx.getParent() instanceof FuncallexpressionContext) {
            fnCallStack.push(idexpr == null ? ident : idexpr);
            return;
        }


        if (ident.This() != null) {
            // TODO (alexsaveliev) - should "this" refer to type?
            typeStack.push(currentClass == null ? UNKNOWN : currentClass);
            fnCallStack.push(null);
            return;
        }

        if (idexpr == null) {
            typeStack.push(UNKNOWN);
            fnCallStack.push(null);
            return;
        }

        String varName = idexpr.getText();
        LookupResult<Variable> lookup = context.lookup(varName);
        String type;
        if (lookup == null) {
            // TODO: namespaces
            if (support.infos.get(varName) != null) {
                // type name like "Foo" in Foo.instance.bar()
                type = varName;
                Ref typeRef = support.ref(ident);
                typeRef.defKey = new DefKey(null, type);
                support.emit(typeRef);
            } else {
                // shorthand member notation
                varName = context.currentScope().getPathTo(varName, PATH_SEPARATOR);
                if (support.infos.get(varName) == null) {
                    type = UNKNOWN;
                } else {
                    type = varName;
                    Ref typeRef = support.ref(ident);
                    typeRef.defKey = new DefKey(null, type);
                    support.emit(typeRef);
                }
            }
        } else {
            type = lookup.getValue().getType();
            Ref identRef = support.ref(idexpr);
            identRef.defKey = new DefKey(null, lookup.getScope().getPathTo(varName, PATH_SEPARATOR));
            support.emit(identRef);
        }
        typeStack.push(type);
    }

    @Override
    public void exitMemberaccessexpression(MemberaccessexpressionContext ctx) {

        // foo.bar, foo.bar(), foo->bar, and foo->bar()
        boolean isFnCall = ctx.getParent() instanceof FuncallexpressionContext;
        IdexpressionContext ident = ctx.idexpression();

        String parent = typeStack.pop();
        if (parent == UNKNOWN) {
            // cannot resolve parent
            if (isFnCall) {
                fnCallStack.push(ident);
            }
            typeStack.push(UNKNOWN);
            return;
        }
        TypeInfo<Scope, String> props = support.infos.get(parent);
        if (props == null) {
            if (isFnCall) {
                fnCallStack.push(ident);
            }
            typeStack.push(UNKNOWN);
            return;
        }

        if (isFnCall) {
            // will deal later
            fnCallStack.push(ident);
            typeStack.push(parent);
            return;
        }

        String varOrPropName = ident.getText();
        String type = props.getProperty(DefKind.VARIABLE, varOrPropName);
        if (type == null) {
            type = UNKNOWN;
        } else {
            Ref propRef = support.ref(ident);
            propRef.defKey = new DefKey(null, props.getData().getPathTo(varOrPropName, PATH_SEPARATOR));
            support.emit(propRef);
        }
        typeStack.push(type);
    }

    @Override
    public void exitCastpostfixexpression(CastpostfixexpressionContext ctx) {
        TypespecifierContext typeCtx = getTypeSpecifier(ctx.typeid().typespecifierseq());
        if (typeCtx == null) {
            typeStack.push(UNKNOWN);
            return;
        }
        // TODO: namespaces
        typeStack.push(processTypeSpecifier(typeCtx));
    }

    @Override
    public void exitExplicittypeconversionexpression(ExplicittypeconversionexpressionContext ctx) {
        SimpletypespecifierContext simpleTypeCtx = ctx.simpletypespecifier();
        if (simpleTypeCtx != null) {
            TypenameContext typeNameSpec = simpleTypeCtx.typename();
            if (typeNameSpec == null) {
                // basic types
                typeStack.push(simpleTypeCtx.getText());
            } else {
                // TODO: namespaces
                typeStack.push(processDeclarationType(typeNameSpec));
            }
        }
    }

    @Override
    public void exitFuncallexpression(FuncallexpressionContext ctx) {
        String signature = signature(ctx.expressionlist());

        if (!(ctx.postfixexpression() instanceof PrimarypostfixexpressionContext)) {
            // foo.bar()
            String parent = typeStack.pop();
            if (parent == UNKNOWN) {
                typeStack.push(UNKNOWN);
                fnCallStack.pop();
                return;
            }
            TypeInfo<Scope, String> props = support.infos.get(parent);
            if (props == null) {
                typeStack.push(UNKNOWN);
                fnCallStack.pop();
                return;
            }
            processFnCallRef(props, signature, false, null);
            return;
        }
        // bar() or Bar() - function or ctor
        ParserRuleContext fnCallNameCtx = fnCallStack.peek();

        TypeInfo<Scope, String> props;

        String className;
        boolean isCtor = false;

        if (fnCallNameCtx != null) {
            isCtor = true;
            className = fnCallNameCtx.getText();
            props = support.infos.get(className);
            if (props == null) {
                // maybe inner class?
                className = context.currentScope().getPathTo(className, PATH_SEPARATOR);
                props = support.infos.get(className);
            }
        } else {
            props = null;
            className = null;
        }

        if (props == null) {
            props = support.infos.getRoot();
            isCtor = false;
            className = currentClass;
        }
        processFnCallRef(props, signature, isCtor, className);
    }

    /**
     * Emits base classes in "class foo: bar"
     */
    private void emitBaseClasses(BasespecifierlistContext classes) {
        if (classes == null) {
            return;
        }
        // TODO : namespaces?
        Token name = classes.basespecifier().basetypespecifier().classordecltype().classname().Identifier().getSymbol();
        Ref typeRef = support.ref(name);
        typeRef.defKey = new DefKey(null, name.getText());
        support.emit(typeRef);
        emitBaseClasses(classes.basespecifierlist());
    }

    /**
     * Handles type part of simple declaration
     */
    private String processDeclarationType(SimpledeclarationContext ctx) {
        TypespecifierContext typeSpec = getDeclTypeSpecifier(ctx.declspecifierseq());
        if (typeSpec == null) {
            return null;
        }
        return processTypeSpecifier(typeSpec);
    }

    /**
     * Extracts type specifier from type specifier sequence
     */
    private TypespecifierContext getTypeSpecifier(TypespecifierseqContext ctx) {
        if (ctx == null) {
            return null;
        }
        TypespecifierContext typeSpec = ctx.typespecifier();
        if (typeSpec != null) {
            return typeSpec;
        }
        return getTypeSpecifier(ctx.typespecifierseq());
    }

    /**
     * Extracts type specifier from type specifier sequence
     */
    private TypespecifierContext getDeclTypeSpecifier(DeclspecifierseqContext ctx) {
        if (ctx == null) {
            return null;
        }
        DeclspecifierContext spec = ctx.declspecifier();
        if (spec == null) {
            return null;
        }
        TypespecifierContext typeSpec = spec.typespecifier();
        if (typeSpec != null) {
            return typeSpec;
        }
        return getDeclTypeSpecifier(ctx.declspecifierseq());
    }


    /**
     * Handles type specifier
     */
    private String processTypeSpecifier(TypespecifierContext typeSpec) {
        TrailingtypespecifierContext trailingTypeSpec = typeSpec.trailingtypespecifier();
        if (trailingTypeSpec == null) {
            return null;
        }
        SimpletypespecifierContext simpleTypeSpec = trailingTypeSpec.simpletypespecifier();
        if (simpleTypeSpec == null) {
            return null;
        }
        TypenameContext typeNameSpec = simpleTypeSpec.typename();
        if (typeNameSpec == null) {
            // basic types
            return simpleTypeSpec.getText();
        }

        // TODO: namespaces
        return processDeclarationType(typeNameSpec);
    }

    /**
     * Handles type part of simple declaration
     */
    private String processDeclarationType(TypenameContext typeNameSpec) {
        ClassnameContext classnameSpec = typeNameSpec.classname();
        if (classnameSpec != null) {
            return processTypeRef(classnameSpec.Identifier());
        }
        EnumnameContext enumnameSpec = typeNameSpec.enumname();
        if (enumnameSpec != null) {
            return processTypeRef(enumnameSpec.Identifier());
        }
        TypedefnameContext typedefSpec = typeNameSpec.typedefname();
        if (typedefSpec != null) {
            return processTypeRef(typedefSpec.Identifier());
        }
        SimpletemplateidContext templateIdSpec = typeNameSpec.simpletemplateid();
        if (templateIdSpec != null) {
            return processTypeRef(templateIdSpec.templatename().Identifier());
        }
        return null;
    }

    /**
     * Emits type ref denoted by given identifier, returns type name
     */
    private String processTypeRef(TerminalNode identifier) {
        Token token = identifier.getSymbol();
        String typeName = token.getText();
        Ref typeRef = support.ref(token);
        // TODO: namespaces
        typeRef.defKey = new DefKey(null, typeName);
        support.emit(typeRef);
        return typeName;
    }

    /**
     * Handles variables in "foo bar,baz" statements
     */
    private void processDeclarationVariables(InitdeclaratorlistContext variables, String typeName) {
        if (variables == null) {
            return;
        }
        processDeclarationVariable(variables.initdeclarator(), typeName);
        processDeclarationVariables(variables.initdeclaratorlist(), typeName);
    }

    /**
     * Handles single variable in "foo bar,baz" statements
     */
    private void processDeclarationVariable(InitdeclaratorContext var, String typeName) {
        ParserRuleContext ident = getIdentifier(var.declarator());
        if (ident == null) {
            return;
        }
        Def varDef = support.def(ident, DefKind.VARIABLE);
        varDef.defKey = new DefKey(null, context.currentScope().getPathTo(varDef.name, PATH_SEPARATOR));
        varDef.format(StringUtils.EMPTY, typeName == null ? StringUtils.EMPTY : typeName, DefData.SEPARATOR_SPACE);
        varDef.defData.setKind(DefKind.VARIABLE);
        context.currentScope().put(varDef.name, new Variable(typeName));
        support.emit(varDef);
    }

    /**
     * Extracts identifier information
     */
    private ParserRuleContext getIdentifier(DeclaratorContext ctx) {

        NoptrdeclaratorContext noPtr = ctx.noptrdeclarator();
        if (noPtr != null) {
            return getIdentifier(noPtr);
        }
        return getIdentifier(ctx.ptrdeclarator());
    }

    /**
     * Extracts identifier information
     */
    private ParserRuleContext getIdentifier(PtrdeclaratorContext ctx) {
        if (ctx == null) {
            return null;
        }
        NoptrdeclaratorContext noPtr = ctx.noptrdeclarator();
        if (noPtr != null) {
            return getIdentifier(noPtr);
        }
        return getIdentifier(ctx.ptrdeclarator());
    }

    /**
     * Extracts identifier information
     */
    private ParserRuleContext getIdentifier(NoptrdeclaratorContext ctx) {
        if (ctx == null) {
            return null;
        }
        DeclaratoridContext declId = ctx.declaratorid();
        if (declId != null) {
            return declId.idexpression();
        }
        NoptrdeclaratorContext noPtr = ctx.noptrdeclarator();
        if (noPtr != null) {
            return getIdentifier(noPtr);
        }
        return getIdentifier(ctx.ptrdeclarator());
    }

    /**
     * Extracts parameters information
     */
    private ParametersandqualifiersContext getParametersAndQualifiers(DeclaratorContext ctx) {
        NoptrdeclaratorContext noPtr = ctx.noptrdeclarator();
        if (noPtr != null) {
            return getParametersAndQualifiers(noPtr);
        }
        return getParametersAndQualifiers(ctx.ptrdeclarator());
    }

    /**
     * Extracts parameters information
     */
    private ParametersandqualifiersContext getParametersAndQualifiers(PtrdeclaratorContext ctx) {
        if (ctx == null) {
            return null;
        }
        NoptrdeclaratorContext noPtr = ctx.noptrdeclarator();
        if (noPtr != null) {
            return getParametersAndQualifiers(noPtr);
        }
        return getParametersAndQualifiers(ctx.ptrdeclarator());
    }

    /**
     * Extracts parameters information
     */
    private ParametersandqualifiersContext getParametersAndQualifiers(NoptrdeclaratorContext ctx) {
        if (ctx == null) {
            return null;
        }
        ParametersandqualifiersContext params = ctx.parametersandqualifiers();
        if (params != null) {
            return params;
        }
        NoptrdeclaratorContext noPtr = ctx.noptrdeclarator();
        if (noPtr != null) {
            return getParametersAndQualifiers(noPtr);
        }
        return getParametersAndQualifiers(ctx.ptrdeclarator());
    }

    /**
     * Collects function parameters
     */
    private void processFunctionParameters(ParameterdeclarationlistContext ctx,
                                           FunctionParameters params) {
        if (ctx == null) {
            return;
        }
        processFunctionParameters(ctx.parameterdeclaration(), params);
        processFunctionParameters(ctx.parameterdeclarationlist(), params);
    }

    /**
     * Collects function parameters. Handles single parameter
     */
    private void processFunctionParameters(ParameterdeclarationContext param,
                                           FunctionParameters params) {
        ParserRuleContext paramNameCtx = getIdentifier(param.declarator());
        TypespecifierContext paramTypeCtx = getDeclTypeSpecifier(param.declspecifierseq());
        // TODOL: namespaces
        String paramType = processTypeSpecifier(paramTypeCtx);
        Def paramDef = support.def(paramNameCtx, DefKind.ARGUMENT);
        paramDef.format(StringUtils.EMPTY, paramType, DefData.SEPARATOR_SPACE);
        paramDef.defData.setKind(DefKind.ARGUMENT);
        FunctionParameter fp = new FunctionParameter(paramDef.name,
                paramType,
                paramType + ' ' + paramDef.name,
                "_",
                paramDef);
        params.params.add(fp);
    }

    /**
     * Handles class members
     */
    private void processMembers(MemberspecificationContext ctx) {
        if (ctx == null) {
            return;
        }
        // head
        MemberdeclarationContext member = ctx.memberdeclaration();
        if (member != null) {
            TypespecifierContext typeCtx = getDeclTypeSpecifier(member.declspecifierseq());
            String type = null;
            if (typeCtx != null) {
                type = processTypeSpecifier(typeCtx);
            }
            processMembers(member.memberdeclaratorlist(), type);
        }
        // tail
        processMembers(ctx.memberspecification());
    }

    /**
     * Handles class members
     */
    private void processMembers(MemberdeclaratorlistContext members, String type) {
        if (members == null) {
            return;
        }
        MemberdeclaratorContext member = members.memberdeclarator();
        if (member != null) {
            processMember(member, type);
        }
        processMembers(members.memberdeclaratorlist(), type);
    }

    /**
     * Handles single class member
     */
    private void processMember(MemberdeclaratorContext member, String type) {
        ParserRuleContext ident = getIdentifier(member.declarator());
        if (ident == null) {
            return;
        }
        String name = ident.getText();
        Def memberDef = support.def(ident, DefKind.MEMBER);
        memberDef.defKey = new DefKey(null, context.currentScope().getPathTo(name, PATH_SEPARATOR));
        memberDef.format(StringUtils.EMPTY, type, DefData.SEPARATOR_SPACE);
        memberDef.defData.setKind(DefKind.MEMBER);
        support.emit(memberDef);
        Variable variable = new Variable(type);
        context.currentScope().put(name, variable);
        support.infos.setProperty(context.getPath(PATH_SEPARATOR), DefKind.VARIABLE, name, type);
    }

    /**
     * Constructs function signature based on parameters
     */
    private String signature(ExpressionlistContext ctx) {
        if (ctx == null) {
            return StringUtils.EMPTY;
        }
        Collection<String> params = new LinkedList<>();
        processSignature(ctx.initializerlist(), params);
        return StringUtils.join(params, ',');
    }

    /**
     * Recursively processes function call arguments to build a signature
     */
    private void processSignature(InitializerlistContext ctx, Collection<String> params) {
        if (ctx == null) {
            return;
        }
        if (ctx.initializerclause() != null) {
            params.add("_");
        }
        processSignature(ctx.initializerlist(), params);
    }

    private void processFnCallRef(TypeInfo<Scope, String> props,
                                  String signature,
                                  boolean isCtor,
                                  String className) {
        ParserRuleContext fnIdent = fnCallStack.pop();
        if (fnIdent == null) {
            return;
        }

        String methodName;
        if (isCtor) {
            int pos = className.lastIndexOf(PATH_SEPARATOR);
            if (pos >= 0) {
                methodName = className.substring(pos + 1);
            } else {
                methodName = className;
            }
        } else {
            methodName = fnIdent.getText();
        }
        // looking for matching function
        String fnPath = methodName + '(' + signature + ')';

        String type = null;

        if (className != null) {
            // lookup in current class
            TypeInfo<Scope, String> currentProps = support.infos.get(className);
            type = currentProps.getProperty(DefKind.FUNCTION, fnPath);
            if (type != null) {
                props = currentProps;
            }
        }

        if (type == null) {
            if (isCtor) {
                type = className;
            } else {
                type = props.getProperty(DefKind.FUNCTION, fnPath);
            }
        }

        if (type != null) {
            Ref methodRef = support.ref(fnIdent);
            Scope scope = props.getData();
            if (scope == null) {
                scope = context.getRoot();
            }
            methodRef.defKey = new DefKey(null, scope.getPathTo(fnPath, PATH_SEPARATOR));
            support.emit(methodRef);
            typeStack.push(type);
        } else {
            typeStack.push(UNKNOWN);
        }
    }

    private static class FunctionParameters {
        Collection<FunctionParameter> params = new LinkedList<>();

        String getRepresentation() {
            StringBuilder ret = new StringBuilder();
            boolean first = true;
            for (FunctionParameter param : params) {
                if (!first) {
                    ret.append(", ");
                } else {
                    first = false;
                }
                ret.append(param.repr);
            }
            return ret.toString();
        }

        String getSignature() {
            StringBuilder ret = new StringBuilder();
            boolean first = true;
            for (FunctionParameter param : params) {
                if (!first) {
                    ret.append(',');
                } else {
                    first = false;
                }
                ret.append(param.signature);
            }
            return ret.toString();
        }

    }

    private static class FunctionParameter {

        String name;
        String type;
        String repr;
        String signature;
        Def def;

        FunctionParameter(String name, String type, String repr, String signature, Def def) {
            this.name = name;
            this.type = type;
            this.repr = repr;
            this.signature = signature;
            this.def = def;
        }
    }

}