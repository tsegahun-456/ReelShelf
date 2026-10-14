#!/usr/bin/env python3
"""
Prepares an ultra-clean, professionally formatted Markdown document 
specifically optimized for Pandoc compilation to Microsoft Word (.docx) 
and direct import into Google Docs.
"""

import re
import os

source_path = '/home/tsega/Documents/java-web/docs/SRS_COMPREHENSIVE.md'
output_path = '/home/tsega/Documents/java-web/docs/SRS_PREPARED_FOR_DOCX.md'

with open(source_path, 'r', encoding='utf-8') as f:
    text = f.read()

# 1. Remove raw ```mermaid ... ``` code blocks completely
# (We already have high-resolution PNG images rendered for each diagram)
text = re.sub(r'```mermaid[\s\S]*?```', '', text)

# 2. Native OpenXML page break snippet for Word and Google Docs
PAGE_BREAK = '\n\n```{=openxml}\n<w:p><w:r><w:br w:type="page"/></w:r></w:p>\n```\n\n'

# 3. Add Page Breaks before major Chapters & Table of Contents
text = text.replace('## Table of Contents', PAGE_BREAK + '## Table of Contents')
text = text.replace('# CHAPTER ONE: INTRODUCTION', PAGE_BREAK + '# CHAPTER ONE: INTRODUCTION')
text = text.replace('# CHAPTER TWO: EXISTING SYSTEM ANALYSIS', PAGE_BREAK + '# CHAPTER TWO: EXISTING SYSTEM ANALYSIS')
text = text.replace('# CHAPTER THREE: SYSTEM ANALYSIS', PAGE_BREAK + '# CHAPTER THREE: SYSTEM ANALYSIS')
text = text.replace('# CHAPTER FOUR: SYSTEM MODELING AND DESIGN', PAGE_BREAK + '# CHAPTER FOUR: SYSTEM MODELING AND DESIGN')
text = text.replace('# CHAPTER FIVE: CONCLUSION AND RECOMMENDATIONS', PAGE_BREAK + '# CHAPTER FIVE: CONCLUSION AND RECOMMENDATIONS')
text = text.replace('# References', PAGE_BREAK + '# References')

# 4. Standardize Image dimensions to width=6.2in and add centered italic captions
# Replace: ![Caption](path) with ![Caption](path){width=6.2in}
def standardize_images(match):
    caption = match.group(1)
    img_path = match.group(2)
    return f'\n\n![{caption}]({img_path}){{width=6.2in}}\n\n*{caption}*\n\n'

text = re.sub(r'!\[(.*?)\]\((.*?)\)(?:\{.*?\})?', standardize_images, text)

# 5. Clean up redundant empty lines (max 2 consecutive newlines)
text = re.sub(r'\n{3,}', '\n\n', text)

# 6. Ensure proper spacing around headings
text = re.sub(r'(\n#+\s+[^\n]+)\n([^\n#])', r'\1\n\n\2', text)

# 7. Clean up table cell breaks (<br> and <br/>) to clean readable breaks
text = re.sub(r'<br\s*/?>', '<br />', text)

with open(output_path, 'w', encoding='utf-8') as f:
    f.write(text)

print(f'Prepared clean document at: {output_path} ({len(text)} characters)')
