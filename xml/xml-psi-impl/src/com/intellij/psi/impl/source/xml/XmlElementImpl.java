/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.psi.impl.source.xml;

import com.intellij.lang.ASTNode;
import com.intellij.lang.Language;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.impl.source.tree.CompositeElement;
import com.intellij.psi.impl.source.tree.CompositePsiElement;
import com.intellij.psi.impl.source.tree.TreeElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiElementProcessor;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.templateLanguages.OuterLanguageElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlElement;
import com.intellij.psi.xml.XmlElementType;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlText;
import com.intellij.xml.util.XmlPsiUtil;
import net.sf.saxon.expr.parser.Location;
import net.sf.saxon.om.AtomicSequence;
import net.sf.saxon.om.NamespaceBinding;
import net.sf.saxon.om.NodeInfo;
import net.sf.saxon.om.TreeInfo;
import net.sf.saxon.pattern.NodeTest;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.tree.iter.AxisIterator;
import net.sf.saxon.tree.util.FastStringBuffer;
import net.sf.saxon.type.Type;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class XmlElementImpl extends CompositePsiElement implements XmlElement {
  public XmlElementImpl(IElementType type) {
    super(type);
  }

  @Override
  public boolean processElements(PsiElementProcessor processor, PsiElement place){
    return XmlPsiUtil.processXmlElements(this, processor, false);
  }

  public boolean processChildren(PsiElementProcessor processor){
    return XmlPsiUtil.processXmlElementChildren(this, processor, false);
  }

  public XmlElement findElementByTokenType(final IElementType type){
    final XmlElement[] result = new XmlElement[1];
    result[0] = null;

    processElements(new PsiElementProcessor(){
      @Override
      public boolean execute(@NotNull PsiElement element){
        if(element instanceof TreeElement && ((ASTNode)element).getElementType() == type){
          result[0] = (XmlElement)element;
          return false;
        }
        return true;
      }
    }, this);

    return result[0];
  }

  @Override
  public PsiElement getContext() {
    final XmlElement data = getUserData(INCLUDING_ELEMENT);
    if(data != null) return data;
    return getAstParent();
  }

  private PsiElement getAstParent() {
    return super.getParent();
  }

  @Override
  @NotNull
  public PsiElement getNavigationElement() {
    if (!isPhysical()) {
      final XmlElement including = getUserData(INCLUDING_ELEMENT);
      if (including != null) {
        return including;
      }
      PsiElement astParent = getAstParent();
      PsiElement parentNavigation = astParent.getNavigationElement();
      if (parentNavigation.getTextOffset() == getTextOffset()) return parentNavigation;
      return this;
    }
    return super.getNavigationElement();
  }

  @Override
  public PsiElement getParent(){
    return getContext();
  }

  @Override
  @NotNull
  public Language getLanguage() {
    return getContainingFile().getLanguage();
  }

  @Nullable
  protected static String getNameFromEntityRef(final CompositeElement compositeElement, final IElementType xmlEntityDeclStart) {
    final ASTNode node = compositeElement.findChildByType(xmlEntityDeclStart);
    if (node == null) return null;
    ASTNode name = node.getTreeNext();

    if (name != null && name.getElementType() == TokenType.WHITE_SPACE) {
      name = name.getTreeNext();
    }

    if (name != null && name.getElementType() == XmlElementType.XML_ENTITY_REF) {
      final StringBuilder builder = new StringBuilder();

      ((XmlElement)name.getPsi()).processElements(new PsiElementProcessor() {
        @Override
        public boolean execute(@NotNull final PsiElement element) {
          builder.append(element.getText());
          return true;
        }
      }, name.getPsi());
      if (builder.length() > 0) return builder.toString();
    }
    return null;
  }

  @Override
  @NotNull
  public SearchScope getUseScope() {
    return GlobalSearchScope.allScope(getProject());
  }

  @Override
  public boolean isEquivalentTo(final PsiElement another) {

    if (super.isEquivalentTo(another)) return true;
    PsiElement element1 = this;

    // TODO: seem to be only necessary for tag dirs equivalents checking.
    if (element1 instanceof XmlTag && another instanceof XmlTag) {
      if (!element1.isPhysical() && !another.isPhysical()) return element1.getText().equals(another.getText());
    }

    return false;
  }

  @Override
  public boolean skipValidation() {
    Boolean doNotValidate = DO_NOT_VALIDATE.get(this);
    if (doNotValidate != null) return doNotValidate;

    OuterLanguageElement element = PsiTreeUtil.getChildOfType(this, OuterLanguageElement.class);

    if (element == null) {
      // JspOuterLanguageElement is located under XmlText
      for (PsiElement child = this.getFirstChild(); child != null; child = child.getNextSibling()) {
        if (child instanceof XmlText) {
          element = PsiTreeUtil.getChildOfType(child, OuterLanguageElement.class);
          if (element != null) {
            break;
          }
        }
      }
    }
    if (element == null) {
      doNotValidate = false;
    } else {
      PsiFile containingFile = this.getContainingFile();
      doNotValidate = containingFile.getViewProvider().getBaseLanguage() != containingFile.getLanguage();
    }
    putUserData(DO_NOT_VALIDATE, doNotValidate);
    return doNotValidate;
  }

  @Override
  public void clearCaches() {
    super.clearCaches();
    putUserData(DO_NOT_VALIDATE, null);
  }

  @NotNull
  public final NodeInfo getNodeInfo() {
    if (myNodeInfo == null) {
      myNodeInfo = generateNodeInfo();
    }
    return myNodeInfo;
  }

  protected NodeInfo myNodeInfo;

  @NotNull
  protected NodeInfo generateNodeInfo() {
    return new XmlElementNodeInfo();
  }

  protected class XmlElementNodeInfo implements NodeInfo {

    private String mySystemId;

    @Override
    public TreeInfo getTreeInfo() {
      return null;
    }

    @Override
    public int getNodeKind() {
      XmlElementImpl instance = XmlElementImpl.this;
      if (instance instanceof XmlTagImpl) {
        return Type.ELEMENT;
      } else if (instance instanceof XmlAttributeImpl) {
        return Type.ATTRIBUTE;
      } else if (instance instanceof XmlTextImpl) {
        return Type.TEXT;
      } else if (instance instanceof XmlCommentImpl) {
        return Type.COMMENT;
      } else if (instance instanceof XmlDocumentImpl) {
        return Type.DOCUMENT;
      } else if (instance instanceof XmlProcessingInstructionImpl) {
        return Type.PROCESSING_INSTRUCTION;
      }
      return Type.NODE;
    }

    @Override
    public void setSystemId(String systemId) {
      mySystemId = systemId;
    }

    @Override
    public String getSystemId() {
      return mySystemId;
    }

    @Override
    public Location saveLocation() {
      return null;
    }

    @Override
    public String getBaseURI() {
      return null;
    }

    @Override
    public int compareOrder(NodeInfo other) {
      return 0;
    }

    @Override
    public String getStringValue() {
      return XmlElementImpl.this.getText();
    }

    @Override
    public CharSequence getStringValueCS() {
      return XmlElementImpl.this.getText();
    }

    @Override
    public boolean hasFingerprint() {
      return false;
    }

    @Override
    public int getFingerprint() {
      return 0;
    }

    @Override
    public String getLocalPart() {
      return null;
    }

    @Override
    public String getURI() {
      return null;
    }

    @Override
    public String getDisplayName() {
      return null;
    }

    @Override
    public String getPrefix() {
      return null;
    }

    @Override
    public AtomicSequence atomize() throws XPathException {
      return null;
    }

    @Override
    public NodeInfo getParent() {
      return null;
    }

    @Override
    public AxisIterator iterateAxis(byte axisNumber, NodeTest nodeTest) {
      return null;
    }

    @Override
    public String getAttributeValue(String uri, String local) {
      return null;
    }

    @Override
    public NodeInfo getRoot() {
      return null;
    }

    @Override
    public boolean hasChildNodes() {
      return false;
    }

    @Override
    public void generateId(FastStringBuffer buffer) {

    }

    @Override
    public NamespaceBinding[] getDeclaredNamespaces(NamespaceBinding[] buffer) {
      return new NamespaceBinding[0];
    }
  }
}
