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
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.PopupWindow;
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
  private static final int EXPAND_BUTTON_OVERHANG_DP = 22;
  private static final int EXPANDED_HEIGHT_DP = 180;
  private static final int EXPANDED_VERTICAL_GAP_DP = 8;
  private static final int PANEL_CORNER_RADIUS_DP = 24;

  private final RecyclerView collapsedList;
  private final RecyclerView expandedGrid;
  private final TextView toggleButton;
  private PopupWindow popupWindow;
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
    collapsedList.setPadding(0, 0, dp(12), 0);

    expandedGrid = new RecyclerView(context);
    expandedGrid.setLayoutManager(new GridLayoutManager(getContext(), EXPANDED_SPAN_COUNT));
    expandedGrid.setOverScrollMode(OVER_SCROLL_NEVER);
    expandedGrid.setClipToPadding(false);
    expandedGrid.setPadding(dp(8), dp(8), dp(8), dp(8));
    expandedGrid.setBackground(createPanelBackground());

    toggleButton = new TextView(context);
    toggleButton.setGravity(Gravity.CENTER);
    toggleButton.setTextColor(com.tom.rv2ide.utils.ResourceUtilsKt.resolveAttr(context, R.attr.colorOnSurface));
    toggleButton.setTextSize(18f);
    toggleButton.setText("⌃");
    toggleButton.setBackground(createButtonBackground());
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      toggleButton.setElevation(dp(4));
    }
    toggleButton.setOnClickListener(__ -> toggleExpanded());

    setClipChildren(false);
    setClipToPadding(false);

    addView(
        collapsedList,
        new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    final var toggleParams =
        new LayoutParams(dp(EXPAND_BUTTON_SIZE_DP), dp(EXPAND_BUTTON_SIZE_DP));
    toggleParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
    toggleParams.rightMargin = -dp(EXPAND_BUTTON_OVERHANG_DP);
    addView(toggleButton, toggleParams);

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
    toggleButton.setText(expanded ? "⌄" : "⌃");
    toggleButton.bringToFront();

    if (expanded) {
      showExpandedPanel();
    } else {
      dismissExpandedPanel();
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

  private void showExpandedPanel() {
    if (popupWindow != null && popupWindow.isShowing()) {
      return;
    }

    final int width = Math.max(0, getWidth());
    if (width == 0) {
      post(() -> setExpanded(true));
      return;
    }

    popupWindow =
        new PopupWindow(
            expandedGrid,
            width,
            dp(EXPANDED_HEIGHT_DP),
            false);
    popupWindow.setClippingEnabled(false);
    popupWindow.setOutsideTouchable(true);
    popupWindow.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      popupWindow.setElevation(dp(6));
    }
    popupWindow.setOnDismissListener(
        () -> {
          expanded = false;
          toggleButton.setText("⌃");
          popupWindow = null;
        });

    popupWindow.showAsDropDown(this, 0, -getHeight() - dp(EXPANDED_HEIGHT_DP + EXPANDED_VERTICAL_GAP_DP));
  }

  private void dismissExpandedPanel() {
    if (popupWindow != null) {
      popupWindow.dismiss();
      popupWindow = null;
    }
  }

  private GradientDrawable createPanelBackground() {
    final var drawable = new GradientDrawable();
    drawable.setColor(com.tom.rv2ide.utils.ResourceUtilsKt.resolveAttr(getContext(), R.attr.colorSurface));
    drawable.setCornerRadius(dp(PANEL_CORNER_RADIUS_DP));
    return drawable;
  }

  private GradientDrawable createButtonBackground() {
    final var drawable = new GradientDrawable();
    drawable.setShape(GradientDrawable.OVAL);
    drawable.setColor(com.tom.rv2ide.utils.ResourceUtilsKt.resolveAttr(getContext(), R.attr.colorSurface));
    return drawable;
  }

  @Override
  protected void onDetachedFromWindow() {
    dismissExpandedPanel();
    super.onDetachedFromWindow();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
