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
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.tom.rv2ide.adapters.SymbolInputAdapter;
import com.tom.rv2ide.editor.ui.IDEEditor;
import com.tom.rv2ide.models.EditorQuickItem;
import com.tom.rv2ide.models.Symbol;
import com.tom.rv2ide.utils.EditorQuickInputProvider;
import java.util.List;

public class SymbolInputView extends FrameLayout {

  private static final int EXPANDED_SPAN_COUNT = 8;

  public enum ExpandDirection {
    UP,
    DOWN
  }

  public interface ExpansionChangeListener {
    void onExpansionChanged(boolean expanded, ExpandDirection direction);
  }

  private final RecyclerView collapsedList;
  private final RecyclerView expandedGrid;
  @Nullable private TextView toggleButton;
  @Nullable private ExpansionChangeListener expansionChangeListener;
  private ExpandDirection expandDirection = ExpandDirection.UP;
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

    expandedGrid = new RecyclerView(context);
    expandedGrid.setLayoutManager(new GridLayoutManager(getContext(), EXPANDED_SPAN_COUNT));
    expandedGrid.setOverScrollMode(OVER_SCROLL_NEVER);
    expandedGrid.setClipToPadding(false);
    expandedGrid.setPadding(dp(8), dp(8), dp(8), dp(8));

    addView(
        collapsedList,
        new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    addView(
        expandedGrid,
        new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    setExpanded(false);
  }

  public void bindToggleButton(TextView button) {
    toggleButton = button;
    toggleButton.setOnClickListener(__ -> toggleExpanded());
    updateToggleButton();
  }

  public void setExpandDirection(ExpandDirection direction) {
    expandDirection = direction;
  }

  public void setExpansionChangeListener(@Nullable ExpansionChangeListener listener) {
    expansionChangeListener = listener;
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
    if (this.expanded == expanded) {
      updateToggleButton();
      return;
    }

    this.expanded = expanded;
    collapsedList.setVisibility(expanded ? GONE : VISIBLE);
    expandedGrid.setVisibility(expanded ? VISIBLE : GONE);
    updateToggleButton();

    if (expansionChangeListener != null) {
      expansionChangeListener.onExpansionChanged(expanded, expandDirection);
    }
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

  private void updateToggleButton() {
    if (toggleButton != null) {
      toggleButton.setText(expanded ? "⌄" : "⌃");
    }
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
