/*
 * Copyright (c) 2022, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package dev.choreo.apim;

import io.ballerina.compiler.syntax.tree.FunctionBodyBlockNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.FunctionSignatureNode;
import io.ballerina.compiler.syntax.tree.ModuleMemberDeclarationNode;
import io.ballerina.compiler.syntax.tree.ModulePartNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NodeVisitor;
import io.ballerina.compiler.syntax.tree.ReturnTypeDescriptorNode;
import io.ballerina.compiler.syntax.tree.ServiceDeclarationNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.projects.Document;
import io.ballerina.tools.text.LineRange;
import io.ballerina.tools.text.TextDocumentChange;
import io.ballerina.tools.text.TextEdit;
import io.ballerina.tools.text.TextRange;

import java.util.ArrayList;
import java.util.List;

public class SyntaxTreeTransformer extends NodeVisitor {

    private static final TextRange START_POS = TextRange.from(0, 0);
    private CodeGenerator codegen;
    private List<TextEdit> edits;
    private CodeContext ctx;

    public TextDocumentChange modifyDoc(Document document, CodeGenerator codegen) {
        this.edits = new ArrayList<>();
        this.codegen = codegen;
        visitNode(document.syntaxTree().rootNode());
        this.edits.add(0, TextEdit.from(START_POS, codegen.generateImports()));
        return TextDocumentChange.from(this.edits.toArray(new TextEdit[0]));
    }

    private void visitNode(Node node) {
        CodeContext prevCtx = this.ctx;
        this.ctx = new CodeContext(prevCtx, node);
        node.accept(this);
        this.ctx = prevCtx;
    }

    @Override
    public void visit(ModulePartNode modulePartNode) {
        for (ModuleMemberDeclarationNode member : modulePartNode.members()) {
            if (member.kind() == SyntaxKind.SERVICE_DECLARATION) {
                visitNode(member);
            }
        }
    }

    @Override
    public void visit(ServiceDeclarationNode serviceDeclarationNode) {
        for (Node member : serviceDeclarationNode.members()) {
            if (member.kind() == SyntaxKind.RESOURCE_ACCESSOR_DEFINITION) {
                visitNode(member);
            }
        }
    }

    @Override
    public void visit(FunctionDefinitionNode functionDefinitionNode) {
        visitNode(functionDefinitionNode.functionSignature());
        visitNode(functionDefinitionNode.functionBody());
    }

    @Override
    public void visit(FunctionSignatureNode functionSignature) {
        TextRange cursorPos = TextRange.from(functionSignature.openParenToken().textRange().endOffset(), 0);
        String edit = this.codegen.modifyResourceParamSignature(functionSignature);
        TextEdit params = TextEdit.from(cursorPos, edit);
        edits.add(params);

        if (functionSignature.returnTypeDesc().isEmpty()) {
            return;
        }

        ReturnTypeDescriptorNode returnType = functionSignature.returnTypeDesc().get();
        TextRange returnTypeRange = returnType.type().textRange();
        edit = this.codegen.modifyResourceReturnSignature();
        TextEdit newReturnType = TextEdit.from(returnTypeRange, edit);
        edits.add(newReturnType);
    }

    @Override
    public void visit(FunctionBodyBlockNode funcBody) {
        LineRange closingBraceLR = funcBody.closeBraceToken().lineRange();
        TextRange closingBraceTR = funcBody.closeBraceToken().textRange();
        TextRange start = TextRange.from(closingBraceTR.startOffset() - closingBraceLR.startLine().offset(), 0);
        int nTabs = closingBraceLR.startLine().offset() / 4 + 1;

        String medCtx = this.codegen.generateMediationContextRecord(this.ctx);
        edits.add(TextEdit.from(start, medCtx));

        String code = this.codegen.generateDoBlock(this.ctx, nTabs);
        edits.add(TextEdit.from(start, code));
    }
}
