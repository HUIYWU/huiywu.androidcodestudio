/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 *
 */
package com.tom.rv2ide.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.tom.rv2ide.R;
import com.tom.rv2ide.adapters.SymbolInputAdapter;
import com.tom.rv2ide.editor.ui.IDEEditor;
import com.tom.rv2ide.models.EditorQuickItem;
import com.tom.rv2ide.models.Symbol;
import com.tom.rv2ide.utils.EditorQuickInputProvider;
import java.util.List;

public class SymbolInputView extends FrameLayout {

  private static final int EXPANDED_SPAN_COUNT = 8;
  private static final int EXPAND_BUTTON_SIZE_DP = 40;
  private static final int EXPANDED_HEIGHT_DP = 180;

  private final RecyclerView collapsedList;
  private final RecyclerView expandedGrid;
  private final TextView toggleButton;
  private boolean expanded;

  public SymbolInputView(Context context) {
    this(context, null);
  }

  public SymbolInputView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public SymbolInputView(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);

    collapsedList = new RecyclerView(context);
    collapsedList.setLayoutManager(
        new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));
    collapsedList.setOverScrollMode(OVER_SCROLL_NEVER);
    collapsedList.setClipToPadding(false);
    collapsedList.setPadding(0, 0, dp(EXPAND_BUTTON_SIZE_DP), 0);

    expandedGrid = new RecyclerView(context);
    expandedGrid.setLayoutManager(new GridLayoutManager(getContext(), EXPANDED_SPAN_COUNT));
    expandedGrid.setOverScrollMode(OVER_SCROLL_NEVER);
    expandedGrid.setClipToPadding(false);
    expandedGrid.setPadding(0, 0, dp(EXPAND_BUTTON_SIZE_DP), 0);
    expandedGrid.setVisibility(GONE);

    toggleButton = new TextView(context);
    toggleButton.setGravity(Gravity.CENTER);
    toggleButton.setTextColor(com.tom.rv2ide.utils.ResourceUtilsKt.resolveAttr(context, R.attr.colorOnSurface));
    toggleButton.setTextSize(18f);
    toggleButton.setText("⌃");
    toggleButton.setOnClickListener(__ -> toggleExpanded());

    addView(
        collapsedList,
        new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    final var toggleParams =
        new LayoutParams(dp(EXPAND_BUTTON_SIZE_DP), ViewGroup.LayoutParams.MATCH_PARENT);
    toggleParams.gravity = Gravity.END;
    addView(toggleButton, toggleParams);

    addView(
        expandedGrid,
        new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    setExpanded(false);
  }

  public void refresh(IDEEditor editor, List<Symbol> symbols) {
    final var quickItems =
        symbols == null || symbols.isEmpty()
            ? EditorQuickInputProvider.INSTANCE.plainTextItems()
            : EditorQuickInputProvider.INSTANCE.toQuickItems(symbols);

    refreshAdapter(collapsedList, editor, quickItems);
    refreshAdapter(expandedGrid, editor, quickItems);
    setExpanded(false);
  }

  public boolean isExpanded() {
    return expanded;
  }

  public void expand() {
    setExpanded(true);
  }

  public void collapse() {
    setExpanded(false);
  }

  public void toggleExpanded() {
    setExpanded(!expanded);
  }

  public void setExpanded(boolean expanded) {
    this.expanded = expanded;
    collapsedList.setVisibility(expanded ? GONE : VISIBLE);
    expandedGrid.setVisibility(expanded ? VISIBLE : GONE);
    toggleButton.setText(expanded ? "⌄" : "⌃");
    toggleButton.bringToFront();
    updateParentHeight(expanded);
  }

  public void endItemAnimations() {
    if (collapsedList.getItemAnimator() != null) {
      collapsedList.getItemAnimator().endAnimations();
    }
    if (expandedGrid.getItemAnimator() != null) {
      expandedGrid.getItemAnimator().endAnimations();
    }
  }

  private void refreshAdapter(
      RecyclerView recyclerView, IDEEditor editor, List<EditorQuickItem> quickItems) {
    final var adapter = recyclerView.getAdapter();
    if (adapter instanceof SymbolInputAdapter) {
      ((SymbolInputAdapter) adapter).refresh(editor, quickItems);
    } else {
      recyclerView.setAdapter(new SymbolInputAdapter(editor, quickItems));
    }
  }

  private void updateParentHeight(boolean expanded) {
    final var parent = getParent();
    if (!(parent instanceof View)) {
      return;
    }

    final var parentView = (View) parent;
    final var layoutParams = parentView.getLayoutParams();
    if (layoutParams == null) {
      return;
    }

    final int targetHeight =
        expanded
            ? dp(EXPANDED_HEIGHT_DP)
            : getResources().getDimensionPixelSize(R.dimen.editor_sheet_collapsed_height);
    if (layoutParams.height == targetHeight) {
      return;
    }

    layoutParams.height = targetHeight;
    parentView.setLayoutParams(layoutParams);
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
