package com.sourcegraph.toolchain.clojure;

import com.sourcegraph.toolchain.clojure.antlr4.ClojureBaseListener;
import com.sourcegraph.toolchain.clojure.antlr4.ClojureParser;
import com.sourcegraph.toolchain.core.objects.Def;
import com.sourcegraph.toolchain.core.objects.DefData;
import com.sourcegraph.toolchain.core.objects.DefKey;
import com.sourcegraph.toolchain.core.objects.Ref;
import com.sourcegraph.toolchain.language.Scope;
import org.antlr.v4.runtime.ParserRuleContext;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;


class ClojureParseTreeListener extends ClojureBaseListener {


    private static final Logger LOGGER = LoggerFactory.getLogger(ClojureParseTreeListener.class);

    public static final char PATH_SEPARATOR = '.';

    private LanguageImpl support;

    private NamespaceContextResolver nsContextResolver =  NamespaceContextResolver.getInstance();

    private Map<ParserRuleContext, Boolean> defs = new IdentityHashMap<>();

    ClojureParseTreeListener(LanguageImpl support) {
        this.support = support;
    }

    private void enterFunctionWithName(ClojureParser.Fn_nameContext nameCtx, String fnStartKeyword) {
        Def fnDef = support.def(nameCtx, DefKind.FUNC);
        fnDef.format(fnStartKeyword, StringUtils.EMPTY, DefData.SEPARATOR_EMPTY);
        fnDef.defData.setKind(fnStartKeyword);

        emit(fnDef, nsContextResolver.context().currentScope().getPathTo(fnDef.name, PATH_SEPARATOR));

        nsContextResolver.context().currentScope().put(fnDef.name, true);
        defs.put(nameCtx.symbol(), true);

        nsContextResolver.context().enterScope(new Scope<>(nameCtx.getText(), nsContextResolver.context().currentScope().getPrefix()));
    }

    private void saveParametersInScope(List<ClojureParser.ParameterContext> params) {
        for (ClojureParser.ParameterContext param : params) {
            ClojureParser.Parameter_nameContext paramNameCtx = param.parameter_name();
            Def paramDef = support.def(paramNameCtx, DefKind.PARAM);
            paramDef.format("param", StringUtils.EMPTY, DefData.SEPARATOR_EMPTY);
            paramDef.defData.setKind("param");

            emit(paramDef, nsContextResolver.context().currentScope().getPathTo(paramDef.name, PATH_SEPARATOR));

            nsContextResolver.context().currentScope().put(paramNameCtx.getText(), true);
            defs.put(paramNameCtx.symbol(), true);
        }
    }

    @Override public void enterFn_binding(ClojureParser.Fn_bindingContext ctx) {
        nsContextResolver.context().enterScope(nsContextResolver.context().currentScope().next(PATH_SEPARATOR));
        List<ClojureParser.ParameterContext> params = ctx.arguments().parameter();
        saveParametersInScope(params);
    }

    @Override public void exitFn_binding(ClojureParser.Fn_bindingContext ctx) {
        nsContextResolver.context().exitScope();
    }

    @Override public void enterSimple_fn_def(ClojureParser.Simple_fn_defContext ctx) {
        ClojureParser.Fn_nameContext nameCtx = ctx.fn_name();
        String fnStartKeyword = ctx.fn_start().getText();

        enterFunctionWithName(nameCtx, fnStartKeyword);

        List<ClojureParser.ParameterContext> params = ctx.arguments().parameter();
        saveParametersInScope(params);

        //Processing of last arguments
        if (ctx.last_arguments() != null) {
            List<ClojureParser.ParameterContext> lastArgs = ctx.last_arguments().parameters().parameter();
            saveParametersInScope(lastArgs);
        }
    }

    @Override public void exitSimple_fn_def(ClojureParser.Simple_fn_defContext ctx) {
        nsContextResolver.context().exitScope();
    }

    @Override public void enterMulti_fn_def(ClojureParser.Multi_fn_defContext ctx) {
        ClojureParser.Fn_nameContext nameCtx = ctx.fn_name();
        String fnStartKeyword = ctx.fn_start().getText();

        enterFunctionWithName(nameCtx, fnStartKeyword);
    }

    @Override public void exitMulti_fn_def(ClojureParser.Multi_fn_defContext ctx) {
        nsContextResolver.context().exitScope();
    }

    @Override public void enterUndefined_fn_with_name(ClojureParser.Undefined_fn_with_nameContext ctx) {
        ClojureParser.Fn_nameContext nameCtx = ctx.fn_name();
        String fnStartKeyword = ctx.fn_start().getText();

        enterFunctionWithName(nameCtx, fnStartKeyword);
        LOGGER.warn("FUNCTION {} WITH UNDEFINED BODY OR PARAMETERS WAS FOUND, unable to process it fully", ctx.getText());
    }

    @Override public void exitUndefined_fn_with_name(ClojureParser.Undefined_fn_with_nameContext ctx) {
        nsContextResolver.context().exitScope();
    }

    @Override public void enterUndefined_fn(ClojureParser.Undefined_fnContext ctx) {
        LOGGER.warn("UNDEFINED FUNCTION {} WAS FOUND, unable to process it", ctx.getText());
    }

    @Override public void enterSimple_var_def(ClojureParser.Simple_var_defContext ctx) {
        ClojureParser.Var_nameContext nameCtx = ctx.var_name();
        String varStartKeyword = ctx.var_start().getText();

        Def varDef = support.def(nameCtx, DefKind.VAR);
        varDef.format(varStartKeyword, StringUtils.EMPTY, DefData.SEPARATOR_EMPTY);
        varDef.defData.setKind(varStartKeyword);

        emit(varDef, nsContextResolver.context().currentScope().getPathTo(varDef.name, PATH_SEPARATOR));

        nsContextResolver.context().currentScope().put(varDef.name, true);
        defs.put(nameCtx.symbol(), true);
    }

    @Override public void enterUndefined_var_def(ClojureParser.Undefined_var_defContext ctx) {
        LOGGER.warn("UNDEFINED VAR {} WAS FOUND, unable to process it", ctx.getText());
    }

    @Override
    public void enterSymbol(ClojureParser.SymbolContext ctx) {
        //check if it's name of some declaration
        if (defs.containsKey(ctx)) {
            return;
        }

        String pathRes = nsContextResolver.lookup(ctx);
        if (pathRes == null) {
            return;
        }

        Ref ref = support.ref(ctx);
        //LOGGER.debug("lookup res = " + result);
        emit(ref, pathRes);
    }

    @Override public void enterSimple_in_ns_def(ClojureParser.Simple_in_ns_defContext ctx) {
        nsContextResolver.enterNamespace(ctx.ns_name().getText());
    }

    @Override public void enterUndefined_in_ns_def(ClojureParser.Undefined_in_ns_defContext ctx) {
        LOGGER.warn("UNDEFINED NAMESPACE {} WAS FOUND, unable to process it", ctx.getText());
    }

//
//    @Override
//    public void enterNs_def(ClojureParser.Ns_defContext ctx) {
//        String nsName = ctx.ns_name().getText();
//
//        nsContextResolver.enterNamespace(nsName);
//
//        List<ClojureParser.ReferenceContext> refs = ctx.references().reference();
//        for (ClojureParser.ReferenceContext ref : refs) {
//            if (ref.use_reference() != null) {
//                List<ClojureParser.Ref_entityContext> refEnts = ref.use_reference().ref_entities().ref_entity();
//                for (ClojureParser.Ref_entityContext refEnt : refEnts) {
//                    if (refEnt.symbol() != null) {
//                        nsContextResolver.addUsedNamespace(refEnt.getText());
//                    } else {
//                        LOGGER.warn("UNSUPPORTED entity = " + refEnt.getText() + "IN NS :USE REFERENCE");
//                    }
//                }
//            } else if (ref.require_reference() != null) {
//                LOGGER.warn(":REQUIRE REFERENCE = " + ref.getText() + "NOT SUPPORTED IN NS = " + nsName);
//
//            } else if (ref.import_reference() != null) {
//                LOGGER.warn(":IMPORT REFERENCE = " + ref.getText() + "NOT SUPPORTED IN NS = " + nsName);
//
//            } else if (ref.other_reference() != null) {
//                LOGGER.warn("REFERENCE = " + ref.getText() + "NOT SUPPORTED IN NS = " + nsName);
//            }
//        }
//    }
//
//    @Override
//    public void enterLet_form(ClojureParser.Let_formContext ctx) {
//        nsContextResolver.context().enterScope(nsContextResolver.context().currentScope().next(PATH_SEPARATOR));
//
//        List<ClojureParser.BindingContext> bindingsCtx = ctx.bindings().binding();
//        for (ClojureParser.BindingContext bindingCtx : bindingsCtx) {
//            if (bindingCtx.var_name() != null) {
//
//                Def letvarDef = support.def(bindingCtx.var_name(), DefKind.LETVAR);
//                letvarDef.format("letvar", StringUtils.EMPTY, DefData.SEPARATOR_EMPTY);
//                letvarDef.defData.setKind("letvar");
//
//                emit(letvarDef, nsContextResolver.context().currentScope().getPathTo(letvarDef.name, PATH_SEPARATOR));
//
//                nsContextResolver.context().currentScope().put(bindingCtx.var_name().getText(), true);
//                defs.put(bindingCtx.var_name().symbol(), true);
//            } else {
//                LOGGER.warn("UNSUPPORTED BINDING FORM = " + bindingCtx.getText() + "FOR LET DEFINITION = " + ctx.getText() + " WAS FOUND");
//            }
//        }
//    }
//
//    @Override
//    public void exitLet_form(ClojureParser.Let_formContext ctx) {
//        nsContextResolver.context().exitScope();
//    }

    private void emit(Def def, String path) {
        if (!support.firstPass) {
            return;
        }
        String pathWithNs = nsContextResolver.currentNamespaceName() + nsContextResolver.NAMESPACE_SEPARATOR + path;
        def.defKey = new DefKey(null, pathWithNs);
        support.emit(def);
    }

    private void emit(Ref ref, String path) {
        if (support.firstPass) {
            return;
        }
        ref.defKey = new DefKey(null, path);
        support.emit(ref);
    }
}